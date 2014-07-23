/*
 * Copyright (c) 2013, 2014, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.r.runtime.data.closures;

import com.oracle.truffle.r.runtime.data.*;
import com.oracle.truffle.r.runtime.ops.na.NACheck;
import com.oracle.truffle.r.runtime.data.model.*;

public abstract class RToDoubleVectorClosure extends RToVectorClosure implements RAbstractDoubleVector {

    protected final NACheck naCheck;

    public RToDoubleVectorClosure(RAbstractVector vector, NACheck naCheck) {
        super(vector);
        this.naCheck = naCheck;
    }

    public RDoubleVector materialize() {
        int length = getLength();
        double[] result = new double[length];
        for (int i = 0; i < result.length; ++i) {
            double data = getDataAt(i);
            result[i] = data;
        }
        return RDataFactory.createDoubleVector(result, naCheck.hasNeverBeenTrue());
    }

    public Class<?> getElementClass() {
        return RDouble.class;
    }

    @Override
    public RDoubleVector copyWithNewDimensions(int[] newDimensions) {
        return materialize().copyWithNewDimensions(newDimensions);
    }

    @Override
    public Object getDataAtAsObject(int index) {
        return getDataAt(index);
    }
}
