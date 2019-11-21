/*
 * Copyright (c) 2013, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 3 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 3 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 3 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.r.runtime.data.closures;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.r.runtime.RInternalError;
import com.oracle.truffle.r.runtime.RRuntime;
import com.oracle.truffle.r.runtime.RType;
import com.oracle.truffle.r.runtime.data.RStringVector;
import static com.oracle.truffle.r.runtime.data.closures.RClosures.initRegAttributes;
import com.oracle.truffle.r.runtime.data.model.RAbstractContainer;
import com.oracle.truffle.r.runtime.data.model.RIntVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractStringVector;
import com.oracle.truffle.r.runtime.data.model.RAbstractVector;
import com.oracle.truffle.r.runtime.data.nodes.FastPathVectorAccess.FastPathFromStringAccess;
import com.oracle.truffle.r.runtime.data.nodes.SlowPathVectorAccess.SlowPathFromStringAccess;
import com.oracle.truffle.r.runtime.data.nodes.VectorAccess;

public class RToStringVectorClosure extends RAbstractStringVector implements RClosure {
    protected final boolean keepAttributes;
    protected final RAbstractVector vector;

    protected RToStringVectorClosure(RAbstractVector vector, boolean keepAttributes) {
        super(vector.isComplete());
        this.keepAttributes = keepAttributes;
        this.vector = vector;

        if (isMaterialized()) {
            if (keepAttributes) {
                initAttributes(vector.getAttributes());
            } else {
                initRegAttributes(this, vector);
            }
        }
    }

    @TruffleBoundary
    private void copyAttributes(RStringVector materialized) {
        if (keepAttributes) {
            materialized.copyAttributesFrom(this);
        }
    }

    @Override
    public boolean isMaterialized() {
        return vector.isMaterialized();
    }

    @Override
    public Object getInternalStore() {
        return vector.getInternalStore();
    }

    @Override
    public RAbstractVector getDelegate() {
        return vector;
    }

    @Override
    public int getLength() {
        return vector.getLength();
    }

    @Override
    public void setLength(int l) {
        vector.setLength(l);
    }

    @Override
    public int getTrueLength() {
        return vector.getTrueLength();
    }

    @Override
    public void setTrueLength(int l) {
        vector.setTrueLength(l);
    }

    @Override
    public String getDataAt(int index) {
        VectorAccess spa = vector.slowPathAccess();
        return spa.getString(spa.randomAccess(vector), index);
    }

    @Override
    public Object getDelegateDataAt(int index) {
        return vector.getDataAtAsObject(index);
    }

    @Override
    public VectorAccess access() {
        return new FastPathAccess(this, vector.access());
    }

    @Override
    public VectorAccess slowPathAccess() {
        return SLOW_PATH_ACCESS;
    }

    private static final SlowPathFromStringAccess SLOW_PATH_ACCESS = new SlowPathFromStringAccess() {
        @Override
        protected String getStringImpl(AccessIterator accessIter, int index) {
            RToStringVectorClosure vector = (RToStringVectorClosure) accessIter.getStore();
            return vector.getDataAt(index);
        }

        @Override
        protected void setStringImpl(AccessIterator accessIter, int index, String value) {
            RToStringVectorClosure vector = (RToStringVectorClosure) accessIter.getStore();
            vector.setDataAt(vector.getInternalStore(), index, value);
        }
    };

    private static final class FastPathAccess extends FastPathFromStringAccess {

        @Node.Child private VectorAccess delegate;

        FastPathAccess(RAbstractContainer value, VectorAccess delegate) {
            super(value);
            this.delegate = delegate;
        }

        @Override
        public boolean supports(Object value) {
            return delegate.supports(value);
        }

        @Override
        protected Object getStore(RAbstractContainer vector) {
            return super.getStore(((RToStringVectorClosure) vector).vector);
        }

        @Override
        public String getString(RandomIterator iter, int index) {
            return delegate.getString(iter, index);
        }

        @Override
        public String getString(SequentialIterator iter) {
            return delegate.getString(iter);
        }

        @Override
        protected String getStringImpl(AccessIterator accessIterator, int index) {
            throw RInternalError.shouldNotReachHere();
        }

        @Override
        protected void setStringImpl(AccessIterator accessIterator, int index, String value) {
            throw RInternalError.shouldNotReachHere();
        }
    }
}

/*
 * This closure is meant to be used only for implementation of the binary operators.
 */
final class RFactorToStringVectorClosure extends RToStringVectorClosure {

    private final RAbstractStringVector levels;
    private final boolean withNames;

    RFactorToStringVectorClosure(RIntVector vector, RAbstractStringVector levels, boolean withNames, boolean keepAttributes) {
        super(vector, keepAttributes);
        this.levels = levels;
        this.withNames = withNames;
    }

    @Override
    public RAbstractVector castSafe(RType type, ConditionProfile isNAProfile, @SuppressWarnings("hiding") boolean keepAttributes) {
        switch (type) {
            case Character:
                return this;
            default:
                return null;
        }
    }

    @Override
    public String getDataAt(int index) {
        VectorAccess spa = vector.slowPathAccess();
        int val = spa.getInt(spa.randomAccess(vector), index);
        if (!vector.isComplete() && RRuntime.isNA(val)) {
            return RRuntime.STRING_NA;
        } else {
            String l = levels.getDataAt(val - 1);
            if (!levels.isComplete() && RRuntime.isNA(l)) {
                return RRuntime.STRING_NA;
            } else {
                return l;
            }
        }
    }

    @Override
    public RStringVector getNames() {
        return withNames ? super.getNames() : null;
    }
}
