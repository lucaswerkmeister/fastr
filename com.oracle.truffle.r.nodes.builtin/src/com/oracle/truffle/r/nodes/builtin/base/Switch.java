/*
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
 *
 * Copyright (c) 2014, Purdue University
 * Copyright (c) 2014, 2019, Oracle and/or its affiliates
 *
 * All rights reserved.
 */

package com.oracle.truffle.r.nodes.builtin.base;

import static com.oracle.truffle.r.nodes.builtin.CastBuilder.Predef.abstractVectorValue;
import static com.oracle.truffle.r.nodes.builtin.CastBuilder.Predef.atomicIntegerValue;
import static com.oracle.truffle.r.nodes.builtin.CastBuilder.Predef.findFirst;
import static com.oracle.truffle.r.nodes.builtin.CastBuilder.Predef.instanceOf;
import static com.oracle.truffle.r.nodes.builtin.CastBuilder.Predef.size;
import static com.oracle.truffle.r.nodes.builtin.CastBuilder.Predef.stringValue;
import static com.oracle.truffle.r.runtime.RVisibility.CUSTOM;
import static com.oracle.truffle.r.runtime.builtins.RBehavior.COMPLEX;
import static com.oracle.truffle.r.runtime.builtins.RBuiltinKind.PRIMITIVE;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import com.oracle.truffle.r.nodes.builtin.RBuiltinNode;
import com.oracle.truffle.r.nodes.function.PromiseHelperNode.PromiseCheckHelperNode;
import com.oracle.truffle.r.nodes.function.visibility.SetVisibilityNode;
import com.oracle.truffle.r.runtime.ArgumentsSignature;
import com.oracle.truffle.r.runtime.RDeparse;
import com.oracle.truffle.r.runtime.RError;
import com.oracle.truffle.r.runtime.RError.Message;
import com.oracle.truffle.r.runtime.RRuntime;
import com.oracle.truffle.r.runtime.builtins.RBuiltin;
import com.oracle.truffle.r.runtime.data.RArgsValuesAndNames;
import com.oracle.truffle.r.runtime.data.RExpression;
import com.oracle.truffle.r.runtime.data.RList;
import com.oracle.truffle.r.runtime.data.RMissing;
import com.oracle.truffle.r.runtime.data.RNull;
import com.oracle.truffle.r.runtime.data.RPromise;

/**
 * The {@code switch} builtin. When called directly, the "..." arguments are not evaluated before
 * the call, as the semantics requires that only the matched case is evaluated. However, if called
 * indirectly, e.g., by {@do.call}, the arguments will have been evaluated, regardless of the match,
 * so we have to be prepared for both evaluated and unevaluated args, which is encapsulated in
 * {@link PromiseCheckHelperNode}.
 *
 */
@RBuiltin(name = "switch", visibility = CUSTOM, kind = PRIMITIVE, parameterNames = {"..."}, nonEvalArgs = 1, behavior = COMPLEX)
public abstract class Switch extends RBuiltinNode.Arg2 {

    @Child private PromiseCheckHelperNode promiseHelper = new PromiseCheckHelperNode();
    @Child private SetVisibilityNode visibility = SetVisibilityNode.create();

    private final BranchProfile suppliedArgNameIsEmpty = BranchProfile.create();
    private final BranchProfile suppliedArgNameIsNull = BranchProfile.create();
    private final BranchProfile matchedArgIsMissing = BranchProfile.create();
    private final ConditionProfile currentDefaultProfile = ConditionProfile.createBinaryProfile();
    private final ConditionProfile returnValueProfile = ConditionProfile.createBinaryProfile();
    private final BranchProfile notIntType = BranchProfile.create();
    private final ConditionProfile noAlternativesProfile = ConditionProfile.createBinaryProfile();

    static {
        // @formatter:off
        Casts casts = new Casts(Switch.class);
        // first argument must be list or expression or vector of size 1, if it is not String, cast it to integer
        casts.arg(0).defaultError(RError.Message.EXPR_NOT_LENGTH_ONE).
                mustNotBeMissing(RError.Message.EXPR_MISSING).
                returnIf(atomicIntegerValue().or(instanceOf(String.class)).or(instanceOf(RExpression.class))).
                mustBe(abstractVectorValue()).boxPrimitive().mustBe(size(1)).
                returnIf(stringValue(), findFirst().stringElement()).
                returnIf(instanceOf(RList.class)).
                asIntegerVector().findFirst();
        // @formatter:on
    }

    // Note: GnuR returns NULL for lists/expressions, even if they contain a signle number, which
    // could be interpreted as integer

    @Specialization
    @SuppressWarnings("unused")
    protected Object doSwitchList(VirtualFrame frame, RList list, RArgsValuesAndNames optionalArgs) {
        return prepareResult(frame, null);
    }

    @Specialization
    protected Object doSwitchExpr(VirtualFrame frame, RExpression expr, @SuppressWarnings("unused") RArgsValuesAndNames optionalArgs) {
        if (expr.getLength() != 1) {
            throw error(Message.EXPR_NOT_LENGTH_ONE);
        }
        return prepareResult(frame, null);
    }

    @Specialization
    protected Object doSwitch(VirtualFrame frame, String x, RArgsValuesAndNames optionalArgs) {
        return prepareResult(frame, doSwitchString(frame, x, optionalArgs));
    }

    private Object doSwitchString(VirtualFrame frame, String xStr, RArgsValuesAndNames optionalArgs) {
        if (noAlternativesProfile.profile(optionalArgs.getLength() == 0)) {
            warning(RError.Message.NO_ALTERNATIVES_IN_SWITCH);
            return null;
        }
        Object[] optionalArgValues = optionalArgs.getArguments();
        ArgumentsSignature signature = optionalArgs.getSignature();
        for (int i = 0; i < signature.getLength(); i++) {
            final String suppliedArgName = signature.getName(i);
            if (suppliedArgName == null) {
                continue;
            } else if (suppliedArgName.length() == 0) {
                suppliedArgNameIsEmpty.enter();
                throw RError.error(RError.NO_CALLER, RError.Message.ZERO_LENGTH_VARIABLE);
            } else if (xStr.equals(suppliedArgName)) {
                // match, evaluate the associated arg
                Object optionalArgValue = promiseHelper.checkEvaluate(frame, optionalArgValues[i]);
                if (optionalArgValue == RMissing.instance) {
                    matchedArgIsMissing.enter();

                    // Fall-through: If the matched value is missing, take the next non-missing
                    for (int j = i + 1; j < optionalArgValues.length; j++) {
                        Object val = promiseHelper.checkEvaluate(frame, optionalArgValues[j]);
                        if (val != RMissing.instance) {
                            return val;
                        }
                    }

                    // No non-missing value: invisible null
                    return null;
                } else {
                    // Default: Matched name has a value
                    return optionalArgValue;
                }
            }
        }
        // We didn't find a match, so check for default(s)
        Object currentDefault = null;
        for (int i = 0; i < signature.getLength(); i++) {
            final String suppliedArgName = signature.getName(i);
            if (suppliedArgName == null) {
                suppliedArgNameIsNull.enter();
                Object optionalArg = optionalArgValues[i];
                if (currentDefaultProfile.profile(currentDefault != null)) {
                    throw error(RError.Message.DUPLICATE_SWITCH_DEFAULT, deparseDefault(currentDefault), deparseDefault(optionalArg));
                } else {
                    currentDefault = optionalArg;
                }
            }
        }
        if (returnValueProfile.profile(currentDefault != null)) {
            return promiseHelper.checkVisibleEvaluate(frame, currentDefault);
        } else {
            return null;
        }
    }

    private static String deparseDefault(Object arg) {
        if (arg instanceof RPromise) {
            // We do not want to evaluate the promise,just display the rep
            RPromise p = (RPromise) arg;
            return RDeparse.deparseSyntaxElement(p.getRep().asRSyntaxNode());
        } else {
            return RDeparse.deparse(arg);
        }
    }

    @Specialization
    protected Object doSwitch(VirtualFrame frame, int x, RArgsValuesAndNames optionalArgs) {
        if (RRuntime.isNA(x)) {
            // cast pipeline gives us NA for non-integer value
            notIntType.enter();
            return prepareResult(frame, null);
        }
        return prepareResult(frame, doSwitchInt(frame, x, optionalArgs));
    }

    private Object doSwitchInt(VirtualFrame frame, int index, RArgsValuesAndNames optionalArgs) {
        if (noAlternativesProfile.profile(optionalArgs.getLength() == 0)) {
            warning(RError.Message.NO_ALTERNATIVES_IN_SWITCH);
            return null;
        }
        Object[] optionalArgValues = optionalArgs.getArguments();
        if (index >= 1 && index <= optionalArgValues.length) {
            Object value = promiseHelper.checkVisibleEvaluate(frame, optionalArgValues[index - 1]);
            if (value != null) {
                return value;
            }
            throw error(RError.Message.NO_ALTERNATIVE_IN_SWITCH);
        }
        return null;
    }

    private Object prepareResult(VirtualFrame frame, Object value) {
        if (returnValueProfile.profile(value != null)) {
            visibility.execute(frame, true);
            return value;
        } else {
            visibility.execute(frame, false);
            return RNull.instance;
        }
    }
}
