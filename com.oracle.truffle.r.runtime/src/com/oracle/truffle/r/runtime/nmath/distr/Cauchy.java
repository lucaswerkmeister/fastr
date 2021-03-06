/*
 * Copyright (C) 1998 Ross Ihaka
 * Copyright (c) 1998--2008, The R Core Team
 * Copyright (c) 2016, 2019, Oracle and/or its affiliates
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, a copy is available at
 * https://www.R-project.org/Licenses/
 */
package com.oracle.truffle.r.runtime.nmath.distr;

import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import static com.oracle.truffle.r.runtime.nmath.MathConstants.M_PI;
import static com.oracle.truffle.r.runtime.nmath.TOMS708.fabs;

import com.oracle.truffle.r.runtime.Utils;
import com.oracle.truffle.r.runtime.nmath.DPQ;
import com.oracle.truffle.r.runtime.nmath.DPQ.EarlyReturn;
import com.oracle.truffle.r.runtime.nmath.MathFunctions.Function3_1;
import com.oracle.truffle.r.runtime.nmath.MathFunctions.Function3_2;
import com.oracle.truffle.r.runtime.nmath.RMath;
import com.oracle.truffle.r.runtime.nmath.RMathError;
import com.oracle.truffle.r.runtime.nmath.RandomFunctions.RandFunction2_Double;
import com.oracle.truffle.r.runtime.nmath.RandomFunctions.RandomNumberProvider;
import com.oracle.truffle.r.runtime.nmath.distr.CauchyFactory.RCauchyNodeGen;

public final class Cauchy {
    private Cauchy() {
        // contains only static classes
    }

    @GenerateUncached
    public abstract static class RCauchy extends RandFunction2_Double {
        @Specialization
        public double exec(double location, double scale, RandomNumberProvider rand) {
            if (Double.isNaN(location) || !Double.isFinite(scale) || scale < 0) {
                return RMathError.defaultError();
            }
            if (scale == 0. || !Double.isFinite(location)) {
                return location;
            } else {
                return location + scale * Math.tan(M_PI * rand.unifRand());
            }
        }

        public static RCauchy create() {
            return RCauchyNodeGen.create();
        }

        public static RCauchy getUncached() {
            return RCauchyNodeGen.getUncached();
        }
    }

    public static final class DCauchy implements Function3_1 {

        public static DCauchy create() {
            return new DCauchy();
        }

        public static DCauchy getUncached() {
            return new DCauchy();
        }

        @Override
        public double evaluate(double x, double location, double scale, boolean giveLog) {
            double y;
            /* NaNs propagated correctly */
            if (Double.isNaN(x) || Double.isNaN(location) || Double.isNaN(scale)) {
                return x + location + scale;
            }
            if (scale <= 0) {
                return RMathError.defaultError();
            }

            y = (x - location) / scale;
            return giveLog ? -Math.log(M_PI * scale * (1. + y * y)) : 1. / (M_PI * scale * (1. + y * y));
        }
    }

    public static final class PCauchy implements Function3_2 {

        public static PCauchy create() {
            return new PCauchy();
        }

        public static PCauchy getUncached() {
            return new PCauchy();
        }

        @Override
        public double evaluate(double x, double location, double scale, boolean lowerTail, boolean logP) {
            if (Double.isNaN(x) || Double.isNaN(location) || Double.isNaN(scale)) {
                return x + location + scale;
            }

            if (scale <= 0) {
                return RMathError.defaultError();
            }

            double x2 = (x - location) / scale;
            if (Double.isNaN(x2)) {
                return RMathError.defaultError();
            }

            if (!Double.isFinite(x2)) {
                if (x2 < 0) {
                    return DPQ.rdt0(lowerTail, logP);
                } else {
                    return DPQ.rdt1(lowerTail, logP);
                }
            }

            if (!lowerTail) {
                x2 = -x2;
            }

            /*
             * for large x, the standard formula suffers from cancellation. This is from Morten
             * Welinder thanks to Ian Smith's atan(1/x) :
             */

            // GnuR has #ifdef HAVE_ATANPI where it uses atanpi function, here we only implement the
            // case when atanpi is not available for the moment
            if (fabs(x2) > 1) {
                double y = Math.atan(1 / x2) / M_PI;
                return (x2 > 0) ? DPQ.rdclog(y, logP) : DPQ.rdval(-y, logP);
            } else {
                return DPQ.rdval(0.5 + Math.atan(x2) / M_PI, logP);
            }
        }
    }

    public static final class QCauchy implements Function3_2 {

        public static QCauchy create() {
            return new QCauchy();
        }

        public static QCauchy getUncached() {
            return new QCauchy();
        }

        @Override
        public double evaluate(double pIn, double location, double scale, boolean lowerTailIn, boolean logP) {
            double p = pIn;
            if (Double.isNaN(p) || Double.isNaN(location) || Double.isNaN(scale)) {
                return p + location + scale;
            }
            try {
                DPQ.rqp01check(p, logP);
            } catch (EarlyReturn e) {
                return e.result;
            }
            if (scale <= 0 || !Double.isFinite(scale)) {
                if (scale == 0) {
                    return location;
                }
                return RMathError.defaultError();
            }

            boolean lowerTail = lowerTailIn;
            if (logP) {
                if (p > -1) {
                    /*
                     * when ep := Math.exp(p), tan(pi*ep)= -tan(pi*(-ep))= -tan(pi*(-ep)+pi) =
                     * -tan(pi*(1-ep)) = = -tan(pi*(-Math.expm1(p)) for p ~ 0, Math.exp(p) ~ 1,
                     * tan(~0) may be better than tan(~pi).
                     */
                    if (p == 0.) {
                        /* needed, since 1/tan(-0) = -Inf for some arch. */
                        return location + (lowerTail ? scale : -scale) * Double.POSITIVE_INFINITY;
                    }
                    lowerTail = !lowerTail;
                    p = -Math.expm1(p);
                } else {
                    p = Math.exp(p);
                }
            } else {
                if (p > 0.5) {
                    if (p == 1.) {
                        return location + (lowerTail ? scale : -scale) * Double.POSITIVE_INFINITY;
                    }
                    p = 1 - p;
                    lowerTail = !lowerTail;
                }
            }

            if (Utils.identityEquals(p, 0.5)) {
                return location;
            } // avoid 1/Inf below
            if (p == 0.) {
                return location + (lowerTail ? scale : -scale) * Double.NEGATIVE_INFINITY;
            } // p = 1. is handled above
            return location + (lowerTail ? -scale : scale) / RMath.tanpi(p);
            /* -1/tan(pi * p) = -cot(pi * p) = tan(pi * (p - 1/2)) */
        }
    }
}
