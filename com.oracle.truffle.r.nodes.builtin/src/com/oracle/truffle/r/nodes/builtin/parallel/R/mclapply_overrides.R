#
# Copyright (c) 1995-2014, The R Core Team
# Copyright (c) 2016, 2018, Oracle and/or its affiliates
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation; either version 2 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#

## Derived from snow and parallel packages

eval(expression(
mc.set.children.streams <- function(cl)
{
	if (RNGkind()[1L] == "L'Ecuyer-CMRG") {
		clusterExport(cl, "LEcuyer.seed", envir = RNGenv)
		clusterCall(cl, mc.set.stream)
	}
}), asNamespace("parallel"))

mclapplyExpr <- expression({
mclapply <- function(X, FUN, ..., mc.preschedule = TRUE, mc.set.seed = TRUE,
                     mc.silent = FALSE, mc.cores = getOption("mc.cores", 2L),
                     mc.cleanup = TRUE, mc.allow.recursive = TRUE)
{
    # TODO: warning messages are not quite the same as in original mclapply
	cores <- as.integer(mc.cores)
    if(is.na(cores) || cores < 1L) stop("'mc.cores' must be >= 1")
    .check_ncores(cores)

    if (parallel:::isChild() && !isTRUE(mc.allow.recursive))
        return(lapply(X = X, FUN = FUN, ...))

    if (mc.set.seed) mc.reset.stream()

    cl <- list()
    jobs <- list()
    cleanup <- function() {
		# TODO: forcefully "kill" contexts if mc.cleanup is TRUE
		if (length(cl) > 0) {
			# after cluster initialized
			stopCluster(cl)
		}
	}
    on.exit(cleanup())	
	## Follow lapply
    if(!is.vector(X) || is.object(X)) X <- as.list(X)

	if (mc.set.seed) mc.advance.stream();
		
    if (!mc.preschedule) {              # sequential (non-scheduled)
        FUN <- match.fun(FUN)
        if (length(X) <= cores) { # we can use one-shot parallel
    		cl <- makeSHAREDcluster(length(X))
			# there is no actual fork, so we must set seeds explicitly
			if (mc.set.seed) mc.set.children.streams(cl)	
			res <- tryCatch(parallel::clusterApply(cl, X, FUN, ...),
					error=function(e) warning("function(s) calls resulted in an error"))			
        } else { # more complicated, we have to wait for jobs selectively
    		cl <- makeSHAREDcluster(cores)
			# there is no actual fork, so we must set seeds explicitly
			if (mc.set.seed) mc.set.children.streams(cl)
			res <- tryCatch(clusterApplyLB(cl, X, FUN, ...),
					error=function(e) warning("function(s) calls resulted in an error"))
        }
        return(res)
    }
    ## mc.preschedule = TRUE from here on.
    if (length(X) < cores) cores <- length(X)
    if (cores < 2L) return(lapply(X = X, FUN = FUN, ...))
    sindex <- lapply(seq_len(cores),
                     function(i) seq(i, length(X), by = cores))
    schedule <- lapply(seq_len(cores),
                       function(i) X[seq(i, length(X), by = cores)])
    res <- vector("list", length(X))
    cl <- makeSHAREDcluster(cores)
	# there is no actual fork, so we must set seeds explicitly
	if (mc.set.seed) mc.set.children.streams(cl)	

	job.res <- tryCatch(parallel::parLapply(cl, unlist(schedule, recursive=FALSE), FUN, ...), 
			error=function(e) warning("scheduled core(s) encountered errors in user code"))			
    prevLen <- 1
    for (i in seq_len(cores)) {
        len = length(sindex[[i]])
        job.res[seq(prevLen, prevLen + len - 1)]
        res[sindex[[i]]] <- job.res[seq(prevLen, prevLen + len - 1)]
        prevLen <- prevLen + len
    }
	res	
}; environment(mclapply)<-asNamespace("parallel")})
eval(mclapplyExpr, asNamespace("parallel"))
# seems like we don't need these anymore, but let's make sure
#eval(mclapplyExpr, as.environment("package:parallel"))
