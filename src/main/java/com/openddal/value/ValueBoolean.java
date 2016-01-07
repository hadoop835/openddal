/*
 * Copyright 2014-2015 the original author or authors
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
package com.openddal.value;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Implementation of the BOOLEAN data type.
 */
public class ValueBoolean extends Value {

    /**
     * The precision in digits.
     */
    public static final int PRECISION = 1;

    /**
     * The maximum display size of a boolean.
     * Example: FALSE
     */
    public static final int DISPLAY_SIZE = 5;

    /**
     * Of type Object so that Tomcat doesn't set it to null.
     */
    private static final Object TRUE = new ValueBoolean(true);
    private static final Object FALSE = new ValueBoolean(false);

    private final Boolean value;

    private ValueBoolean(boolean value) {
        this.value = Boolean.valueOf(value);
    }

    /**
     * Get the boolean value for the given boolean.
     *
     * @param b the boolean
     * @return the value
     */
    public static ValueBoolean get(boolean b) {
        return (ValueBoolean) (b ? TRUE : FALSE);
    }

    @Override
    public int getType() {
        return BOOLEAN;
    }

    @Override
    public String getSQL() {
        return getString();
    }

    @Override
    public String getString() {
        return value.booleanValue() ? "TRUE" : "FALSE";
    }

    @Override
    public Value negate() {
        return (ValueBoolean) (value.booleanValue() ? FALSE : TRUE);
    }

    @Override
    public Boolean getBoolean() {
        return value;
    }

    @Override
    protected int compareSecure(Value o, CompareMode mode) {
        boolean v2 = ((ValueBoolean) o).value.booleanValue();
        boolean v = value.booleanValue();
        return (v == v2) ? 0 : (v ? 1 : -1);
    }

    @Override
    public long getPrecision() {
        return PRECISION;
    }

    @Override
    public int hashCode() {
        return value.booleanValue() ? 1 : 0;
    }

    @Override
    public Object getObject() {
        return value;
    }

    @Override
    public void set(PreparedStatement prep, int parameterIndex)
            throws SQLException {
        prep.setBoolean(parameterIndex, value.booleanValue());
    }

    @Override
    public int getDisplaySize() {
        return DISPLAY_SIZE;
    }

    @Override
    public boolean equals(Object other) {
        // there are only ever two instances, so the instance must match
        return this == other;
    }

}
