/*
 * Copyright 2014-2016 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the “License”);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an “AS IS” BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.openddal.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.openddal.command.dml.SetTypes;
import com.openddal.config.Configuration;
import com.openddal.config.SchemaConfig;
import com.openddal.config.TableConfig;
import com.openddal.dbobject.Comment;
import com.openddal.dbobject.DbObject;
import com.openddal.dbobject.Right;
import com.openddal.dbobject.Role;
import com.openddal.dbobject.Setting;
import com.openddal.dbobject.User;
import com.openddal.dbobject.index.Index;
import com.openddal.dbobject.schema.Schema;
import com.openddal.dbobject.schema.SchemaObject;
import com.openddal.dbobject.table.MetaTable;
import com.openddal.dbobject.table.Table;
import com.openddal.dbobject.table.TableMate;
import com.openddal.excutor.ExecutorFactory;
import com.openddal.excutor.PreparedExecutorFactory;
import com.openddal.message.DbException;
import com.openddal.message.ErrorCode;
import com.openddal.message.Trace;
import com.openddal.message.TraceSystem;
import com.openddal.route.RoutingHandler;
import com.openddal.route.RoutingHandlerImpl;
import com.openddal.shards.DataSourceRepository;
import com.openddal.util.BitField;
import com.openddal.util.New;
import com.openddal.util.SourceCompiler;
import com.openddal.util.StringUtils;
import com.openddal.value.CaseInsensitiveMap;
import com.openddal.value.CompareMode;
import com.openddal.value.Value;

/**
 * @author <a href="mailto:jorgie.mail@gmail.com">jorgie li</a>
 */
public class Database {

    public static final String SYSTEM_USER_NAME = "MASTER";

    private final HashMap<String, Role> roles = New.hashMap();
    private final HashMap<String, User> users = New.hashMap();
    private final HashMap<String, Setting> settings = New.hashMap();
    private final HashMap<String, Schema> schemas = New.hashMap();
    private final HashMap<String, Right> rights = New.hashMap();
    private final HashMap<String, Comment> comments = New.hashMap();

    private final Set<Session> userSessions = Collections.synchronizedSet(new HashSet<Session>());

    private final BitField objectIds = new BitField();
    private final DbSettings dbSettings;
    private final DataSourceRepository dsRepository;
    private final Configuration configuration;
    private int nextSessionId;
    private TraceSystem traceSystem;
    private Trace trace;
    private CompareMode compareMode;
    private int allowLiterals = Constants.ALLOW_LITERALS_ALL;
    private volatile boolean closing;
    private boolean ignoreCase;// for data type VARCHAR_IGNORECASE
    private Mode mode = Mode.getInstance(Mode.REGULAR);
    private int maxMemoryRows = SysProperties.MAX_MEMORY_ROWS;
    private int maxOperationMemory = Constants.DEFAULT_MAX_OPERATION_MEMORY;
    private boolean queryStatistics;
    private int queryStatisticsMaxEntries = Constants.QUERY_STATISTICS_MAX_ENTRIES;
    private QueryStatisticsData queryStatisticsData;
    private SourceCompiler compiler;
    private RoutingHandler routingHandler;
    private PreparedExecutorFactory peFactory;

    public Database(Configuration configuration) {
        this.configuration = configuration;
        this.compareMode = CompareMode.getInstance(null, 0);
        this.dbSettings = DbSettings.getInstance(null);

        String sqlMode = configuration.getProperty(SetTypes.MODE, Mode.MY_SQL);
        maxMemoryRows = configuration.getIntProperty(SetTypes.MAX_MEMORY_ROWS, maxMemoryRows);
        Mode settingMode = Mode.getInstance(sqlMode);
        if (settingMode != null) {
            this.mode = settingMode;
        }
        traceSystem = new TraceSystem(null);
        traceSystem.setLevelFile(TraceSystem.ADAPTER);
        trace = traceSystem.getTrace(Trace.DATABASE);
        dsRepository = new DataSourceRepository(this);
        openDatabase();
    }

    private synchronized void openDatabase() {
        User systemUser = new User(this, allocateObjectId(), SYSTEM_USER_NAME);
        systemUser.setAdmin(true);
        systemUser.setPassword(SYSTEM_USER_NAME);
        users.put(SYSTEM_USER_NAME, systemUser);

        Schema mainSchema = new Schema(this, allocateObjectId(), Constants.SCHEMA_MAIN, systemUser, true);
        Schema infoSchema = new Schema(this, -1, "INFORMATION_SCHEMA", systemUser, true);
        schemas.put(mainSchema.getName(), mainSchema);
        schemas.put(infoSchema.getName(), infoSchema);

        Role publicRole = new Role(this, 0, Constants.PUBLIC_ROLE_NAME, true);
        roles.put(Constants.PUBLIC_ROLE_NAME, publicRole);

        Session sysSession = createSession(systemUser);
        try {
            SchemaConfig sc = configuration.getSchemaConfig();
            List<TableConfig> ctList = sc.getTables();
            for (TableConfig tableConfig : ctList) {
                String identifier = tableConfig.getName();
                identifier = identifier(identifier);
                TableMate tableMate = new TableMate(mainSchema, allocateObjectId(), identifier);
                tableMate.setTableRouter(tableConfig.getTableRouter());
                tableMate.setShards(tableConfig.getShards());
                tableMate.setScanLevel(tableConfig.getScanLevel());
                tableMate.loadMataData(sysSession);
                if (tableConfig.isValidation()) {
                    tableMate.check();
                }
                this.addSchemaObject(tableMate);
            }
        } finally {
            sysSession.close();
        }
        for (int type = 0, count = MetaTable.getMetaTableTypeCount();
                type < count; type++) {
            MetaTable m = new MetaTable(infoSchema, -1 - type, type);
            infoSchema.add(m);
        }
    
    }
    


    /**
     * Check if two values are equal with the current comparison mode.
     *
     * @param a the first value
     * @param b the second value
     * @return true if both objects are equal
     */
    public boolean areEqual(Value a, Value b) {
        // can not use equals because ValueDecimal 0.0 is not equal to 0.00.
        return a.compareTo(b, compareMode) == 0;
    }

    /**
     * Compare two values with the current comparison mode. The values may not
     * be of the same type.
     *
     * @param a the first value
     * @param b the second value
     * @return 0 if both values are equal, -1 if the first value is smaller, and
     * 1 otherwise
     */
    public int compare(Value a, Value b) {
        return a.compareTo(b, compareMode);
    }

    /**
     * Compare two values with the current comparison mode. The values must be
     * of the same type.
     *
     * @param a the first value
     * @param b the second value
     * @return 0 if both values are equal, -1 if the first value is smaller, and
     * 1 otherwise
     */
    public int compareTypeSave(Value a, Value b) {
        return a.compareTypeSave(b, compareMode);
    }

    /**
     * Get the trace object for the given module.
     *
     * @param module the module name
     * @return the trace object
     */
    public Trace getTrace(String module) {
        return traceSystem.getTrace(module);
    }

    @SuppressWarnings("unchecked")
    private HashMap<String, DbObject> getMap(int type) {
        HashMap<String, ? extends DbObject> result;
        switch (type) {
            case DbObject.USER:
                result = users;
                break;
            case DbObject.SETTING:
                result = settings;
                break;
            case DbObject.ROLE:
                result = roles;
                break;
            case DbObject.RIGHT:
                result = rights;
                break;
            case DbObject.SCHEMA:
                result = schemas;
                break;
            case DbObject.COMMENT:
                result = comments;
                break;
            default:
                throw DbException.throwInternalError("type=" + type);
        }
        return (HashMap<String, DbObject>) result;
    }

    /**
     * Add a schema object to the database.
     *
     * @param obj the object to add
     */
    public synchronized void addSchemaObject(SchemaObject obj) {
        obj.getSchema().add(obj);
        // trace.debug("addSchemaObject: {0}", obj.getCreateSQL());
    }

    /**
     * Add an object to the database.
     *
     * @param obj the object to add
     */
    public synchronized void addDatabaseObject(DbObject obj) {
        HashMap<String, DbObject> map = getMap(obj.getType());
        String name = obj.getName();
        if (SysProperties.CHECK && map.get(name) != null) {
            DbException.throwInternalError("object already exists");
        }
        map.put(name, obj);
    }

    /**
     * Get the comment for the given database object if one exists, or null if
     * not.
     *
     * @param object the database object
     * @return the comment or null
     */
    public Comment findComment(DbObject object) {
        if (object.getType() == DbObject.COMMENT) {
            return null;
        }
        String key = Comment.getKey(object);
        return comments.get(key);
    }

    /**
     * Get the role if it exists, or null if not.
     *
     * @param roleName the name of the role
     * @return the role or null
     */
    public Role findRole(String roleName) {
        return roles.get(roleName);
    }

    /**
     * Get the schema if it exists, or null if not.
     *
     * @param schemaName the name of the schema
     * @return the schema or null
     */
    public Schema findSchema(String schemaName) {
        Schema schema = schemas.get(schemaName);
        return schema;
    }

    /**
     * Get the setting if it exists, or null if not.
     *
     * @param name the name of the setting
     * @return the setting or null
     */
    public Setting findSetting(String name) {
        return settings.get(name);
    }

    /**
     * Get the user if it exists, or null if not.
     *
     * @param name the name of the user
     * @return the user or null
     */
    public User findUser(String name) {
        return users.get(name);
    }

    /**
     * Get user with the given name. This method throws an exception if the user
     * does not exist.
     *
     * @param name the user name
     * @return the user
     * @throws DbException if the user does not exist
     */
    public User getUser(String name) {
        User user = findUser(name);
        if (user == null) {
            throw DbException.get(ErrorCode.USER_NOT_FOUND_1, name);
        }
        return user;
    }

    /**
     * Create a session for the given user.
     *
     * @param user the user
     * @return the session
     * @throws DbException if the database is in exclusive mode
     */
    public synchronized Session createSession(User user) {
        Session session = new Session(this, user, ++nextSessionId);
        userSessions.add(session);
        trace.info("create session #{0}", session.getId(), "engine");
        return session;
    }

    /**
     * Remove a session. This method is called after the user has disconnected.
     *
     * @param session the session
     */
    public synchronized void removeSession(Session session) {
        if (session != null) {
            userSessions.remove(session);
        }
    }

    /**
     * Immediately close the database.
     */
    public void shutdownImmediately() {
        close();
    }

    /**
     * Close the database.
     */
    public synchronized void close() {
        if (closing) {
            return;
        }
        closing = true;
        if (userSessions.size() > 0) {
            Session[] all = new Session[userSessions.size()];
            userSessions.toArray(all);
            for (Session s : all) {
                try {
                    // must roll back, otherwise the session is removed and
                    // the transaction log that contains its uncommitted
                    // operations as well
                    s.rollback();
                    s.close();
                } catch (DbException e) {
                    trace.error(e, "disconnecting session #{0}", s.getId());
                }
            }
        }
        dsRepository.close();
        traceSystem.close();
    }

    /**
     * Allocate a new object id.
     *
     * @return the id
     */
    public synchronized int allocateObjectId() {
        int i = objectIds.nextClearBit(0);
        objectIds.set(i);
        return i;
    }

    public ArrayList<Comment> getAllComments() {
        return New.arrayList(comments.values());
    }

    public int getAllowLiterals() {
        return allowLiterals;
    }

    public void setAllowLiterals(int value) {
        this.allowLiterals = value;
    }

    public ArrayList<Right> getAllRights() {
        return New.arrayList(rights.values());
    }

    public ArrayList<Role> getAllRoles() {
        return New.arrayList(roles.values());
    }

    /**
     * Get all schema objects.
     *
     * @return all objects of all types
     */
    public ArrayList<SchemaObject> getAllSchemaObjects() {
        ArrayList<SchemaObject> list = New.arrayList();
        for (Schema schema : schemas.values()) {
            list.addAll(schema.getAll());
        }
        return list;
    }

    /**
     * Get all schema objects of the given type.
     *
     * @param type the int type
     */
    public ArrayList<SchemaObject> getAllSchemaObjects(int type) {
        ArrayList<SchemaObject> list = New.arrayList();
        for (Schema schema : schemas.values()) {
            list.addAll(schema.getAll(type));
        }
        return list;
    }

    /**
     * Get all tables and views.
     *
     * @return all objects of that type
     */
    public ArrayList<Table> getAllTablesAndViews() {
        ArrayList<Table> list = New.arrayList();
        for (Schema schema : schemas.values()) {
            list.addAll(schema.getAllTablesAndViews());
        }
        return list;
    }
    
    /**
     * Get the tables with the given name, if any.
     *
     * @param name the table name
     * @return the list
     */
    public ArrayList<Table> getTableOrViewByName(String name) {
        ArrayList<Table> list = New.arrayList();
        for (Schema schema : schemas.values()) {
            Table table = schema.getTableOrViewByName(name);
            if (table != null) {
                list.add(table);
            }
        }
        return list;
    }

    public ArrayList<Schema> getAllSchemas() {
        return New.arrayList(schemas.values());
    }

    public ArrayList<Setting> getAllSettings() {
        return New.arrayList(settings.values());
    }

    public ArrayList<User> getAllUsers() {
        return New.arrayList(users.values());
    }

    public CompareMode getCompareMode() {
        return compareMode;
    }

    public void setCompareMode(CompareMode compareMode) {
        this.compareMode = compareMode;
    }

    /**
     * Get all sessions that are currently connected to the database.
     */
    public Session[] getSessions() {
        ArrayList<Session> list;
        // need to synchronized on userSession, otherwise the list
        // may contain null elements
        synchronized (userSessions) {
            list = New.arrayList(userSessions);
        }
        Session[] array = new Session[list.size()];
        list.toArray(array);
        return array;
    }

    /**
     * Rename a schema object.
     *
     * @param session the session
     * @param obj     the object
     * @param newName the new name
     */
    public synchronized void renameSchemaObject(Session session, SchemaObject obj, String newName) {
        obj.getSchema().rename(obj, newName);
    }

    /**
     * Rename a database object.
     *
     * @param session the session
     * @param obj     the object
     * @param newName the new name
     */
    public synchronized void renameDatabaseObject(Session session, DbObject obj, String newName) {
        int type = obj.getType();
        HashMap<String, DbObject> map = getMap(type);
        if (SysProperties.CHECK) {
            if (!map.containsKey(obj.getName())) {
                DbException.throwInternalError("not found: " + obj.getName());
            }
            if (obj.getName().equals(newName) || map.containsKey(newName)) {
                DbException.throwInternalError("object already exists: " + newName);
            }
        }
        obj.checkRename();
        map.remove(obj.getName());
        obj.rename(newName);
        map.put(newName, obj);
    }

    /**
     * Get the schema. If the schema does not exist, an exception is thrown.
     *
     * @param schemaName the name of the schema
     * @return the schema
     * @throws DbException no schema with that name exists
     */
    public Schema getSchema(String schemaName) {
        Schema schema = findSchema(schemaName);
        if (schema == null) {
            throw DbException.get(ErrorCode.SCHEMA_NOT_FOUND_1, schemaName);
        }
        return schema;
    }

    /**
     * Remove the object from the database.
     *
     * @param session the session
     * @param obj     the object to remove
     */
    public synchronized void removeDatabaseObject(Session session, DbObject obj) {
        String objName = obj.getName();
        int type = obj.getType();
        HashMap<String, DbObject> map = getMap(type);
        if (SysProperties.CHECK && !map.containsKey(objName)) {
            DbException.throwInternalError("not found: " + objName);
        }
        Comment comment = findComment(obj);
        if (comment != null) {
            removeDatabaseObject(session, comment);
        }
        obj.removeChildrenAndResources(session);
        map.remove(objName);
    }

    /**
     * Get the first table that depends on this object.
     *
     * @param obj    the object to find
     * @param except the table to exclude (or null)
     * @return the first dependent table, or null
     */
    public Table getDependentTable(SchemaObject obj, Table except) {
        switch (obj.getType()) {
            case DbObject.COMMENT:
            case DbObject.CONSTRAINT:
            case DbObject.INDEX:
            case DbObject.RIGHT:
            case DbObject.TRIGGER:
            case DbObject.USER:
                return null;
            default:
        }
        HashSet<DbObject> set = New.hashSet();
        for (Table t : getAllTablesAndViews()) {
            if (except == t) {
                continue;
            } else if (Table.VIEW.equals(t.getTableType())) {
                continue;
            }
            set.clear();
            t.addDependencies(set);
            if (set.contains(obj)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Remove an object from the system table.
     *
     * @param session the session
     * @param obj     the object to be removed
     */
    public synchronized void removeSchemaObject(Session session, SchemaObject obj) {
        int type = obj.getType();
        if (type == DbObject.TABLE_OR_VIEW) {
            Table table = (Table) obj;
            if (table.isTemporary() && !table.isGlobalTemporary()) {
                session.removeLocalTempTable(table);
                return;
            }
        } else if (type == DbObject.INDEX) {
            Index index = (Index) obj;
            Table table = index.getTable();
            if (table.isTemporary() && !table.isGlobalTemporary()) {
                session.removeLocalTempTableIndex(index);
                return;
            }
        }
        Comment comment = findComment(obj);
        if (comment != null) {
            removeDatabaseObject(session, comment);
        }
        obj.getSchema().remove(obj);
    }

    public TraceSystem getTraceSystem() {
        return traceSystem;
    }

    /**
     * Commit the current transaction of the given session.
     *
     * @param session the session
     */
    synchronized void commit(Session session) {
        session.setAllCommitted();
    }

    /**
     * Check if the database is in the process of closing.
     *
     * @return true if the database is closing
     */
    public boolean isClosing() {
        return closing;
    }

    public boolean getIgnoreCase() {
        return ignoreCase;
    }

    public void setIgnoreCase(boolean b) {
        ignoreCase = b;
    }

    public int getSessionCount() {
        return userSessions.size();
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public int getMaxOperationMemory() {
        return maxOperationMemory;
    }

    public void setMaxOperationMemory(int maxOperationMemory) {
        this.maxOperationMemory = maxOperationMemory;
    }

    public int getMaxMemoryRows() {
        return maxMemoryRows;
    }

    public void setMaxMemoryRows(int value) {
        this.maxMemoryRows = value;
    }

    public DbSettings getSettings() {
        return dbSettings;
    }
    

    public void setQueryStatistics(boolean b) {
        queryStatistics = b;
        synchronized (this) {
            queryStatisticsData = null;
        }
    }

    public boolean getQueryStatistics() {
        return queryStatistics;
    }

    public void setQueryStatisticsMaxEntries(int n) {
        queryStatisticsMaxEntries = n;
        if (queryStatisticsData != null) {
            synchronized (this) {
                if (queryStatisticsData != null) {
                    queryStatisticsData.setMaxQueryEntries(queryStatisticsMaxEntries);
                }
            }
        }
    }

    public QueryStatisticsData getQueryStatisticsData() {
        if (!queryStatistics) {
            return null;
        }
        if (queryStatisticsData == null) {
            synchronized (this) {
                if (queryStatisticsData == null) {
                    queryStatisticsData = new QueryStatisticsData(queryStatisticsMaxEntries);
                }
            }
        }
        return queryStatisticsData;
    }

    /**
     * Create a new hash map. Depending on the configuration, the key is case
     * sensitive or case insensitive.
     *
     * @param <V> the value type
     * @return the hash map
     */
    public <V> HashMap<String, V> newStringMap() {
        return dbSettings.databaseToUpper ? new HashMap<String, V>() : new CaseInsensitiveMap<V>();
    }

    /**
     * Compare two identifiers (table names, column names,...) and verify they
     * are equal. Case sensitivity depends on the configuration.
     *
     * @param a the first identifier
     * @param b the second identifier
     * @return true if they match
     */
    public boolean equalsIdentifiers(String a, String b) {
        if (a == b || a.equals(b)) {
            return true;
        }
        return !dbSettings.databaseToUpper && a.equalsIgnoreCase(b);
    }

    /**
     * String to database identifier against dbSettings
     *
     * @param identifier
     * @return
     */
    public String identifier(String identifier) {
        identifier = dbSettings.databaseToUpper ? StringUtils.toUpperEnglish(identifier) : identifier;
        return identifier;
    }

    /**
     * @return the configuration
     */
    public Configuration getConfiguration() {
        return configuration;
    }

    public SourceCompiler getCompiler() {
        if (compiler == null) {
            compiler = new SourceCompiler();
        }
        return compiler;
    }

    public RoutingHandler getRoutingHandler() {
        if (routingHandler == null) {
            routingHandler = new RoutingHandlerImpl(this);
        }
        return routingHandler;
    }

    /**
     * @return the dataSourceManager
     */
    public DataSourceRepository getDataSourceRepository() {
        return dsRepository;
    }

    public PreparedExecutorFactory getPreparedExecutorFactory() {
        if (peFactory == null) {
            peFactory = new ExecutorFactory();
        }
        return peFactory;
    }

}
