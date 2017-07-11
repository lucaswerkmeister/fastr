/*
 * Copyright (c) 2013, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.truffle.r.launcher;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.List;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Context.Builder;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

import com.oracle.truffle.r.launcher.RCmdOptions.RCmdOption;

import jline.console.UserInterruptException;

/*
 * TODO:
 * - create a replacement for "executor"
 * - fatal needs to be handled more carefully in nested/spawned contexts
 * - handle R_DEFAULT_PACKAGES in REnvVars
 */

/**
 * Emulates the (Gnu)R command as precisely as possible.
 */
public class RCommand {

    // CheckStyle: stop system..print check
    public static RuntimeException fatal(String message, Object... args) {
        System.out.println("FATAL: " + String.format(message, args));
        System.exit(-1);
        return null;
    }

    public static RuntimeException fatal(Throwable t, String message, Object... args) {
        t.printStackTrace();
        System.out.println("FATAL: " + String.format(message, args));
        System.exit(-1);
        return null;
    }

    public static void main(String[] args) {
        System.exit(doMain(prependCommand(args), null, System.in, System.out, System.err));
        // never returns
        throw fatal("main should never return");
    }

    static String[] prependCommand(String[] args) {
        String[] result = new String[args.length + 1];
        result[0] = "R";
        System.arraycopy(args, 0, result, 1, args.length);
        return result;
    }

    public static int doMain(String[] inArgs, String[] env, InputStream inStream, OutputStream outStream, OutputStream errStream) {
        StartupTiming.timestamp("Main Entered");
        String[] args = inArgs;
        if (System.console() != null) {
            // add "--interactive" to force interactive mode
            RCmdOptions options = RCmdOptions.parseArguments(RCmdOptions.Client.R, args, false);
            if (!options.getBoolean(RCmdOption.INTERACTIVE)) {
                args = new String[inArgs.length + 1];
                args[0] = inArgs[0];
                args[1] = "--interactive";
                System.arraycopy(inArgs, 1, args, 2, inArgs.length - 1);
            }
        }
        RCmdOptions options = RCmdOptions.parseArguments(RCmdOptions.Client.R, args, false);
        options.printHelpAndVersion();
        try (Engine engine = Engine.create()) {
            assert env == null : "re-enable setting environments";
            ConsoleHandler consoleHandler = createConsoleHandler(options, false, inStream, outStream);
            Builder builder = Context.newBuilder().engine(engine);
            try (Context context = builder.arguments("R", options.getArguments()).in(consoleHandler.createInputStream()).out(outStream).err(errStream).build()) {
                consoleHandler.setContext(context);
                StartupTiming.timestamp("VM Created");
                StartupTiming.printSummary();
                return readEvalPrint(context, consoleHandler);
            }
        }
    }

    public static ConsoleHandler createConsoleHandler(RCmdOptions options, boolean embedded, InputStream inStream, OutputStream outStream) {
        /*
         * Whether the input is from stdin, a file (-f), or an expression on the command line (-e)
         * it goes through the console. N.B. -f and -e can't be used together and this is already
         * checked.
         */
        RStartParams rsp = new RStartParams(options, false);
        String fileArgument = rsp.getFileArgument();
        if (fileArgument != null) {
            List<String> lines;
            try {
                /*
                 * If initial==false, ~ expansion will not have been done and the open will fail.
                 * It's harmless to always do it.
                 */
                File file = fileArgument.startsWith("~") ? new File(System.getProperty("user.home") + fileArgument.substring(1)) : new File(fileArgument);
                lines = Files.readAllLines(file.toPath());
            } catch (IOException e) {
                throw fatal("cannot open file '%s': No such file or directory", fileArgument);
            }
            return new StringConsoleHandler(lines, outStream);
        } else if (options.getStringList(RCmdOption.EXPR) != null) {
            List<String> exprs = options.getStringList(RCmdOption.EXPR);
            for (int i = 0; i < exprs.size(); i++) {
                exprs.set(i, unescapeSpace(exprs.get(i)));
            }
            return new StringConsoleHandler(exprs, outStream);
        } else {
            boolean isInteractive = options.getBoolean(RCmdOption.INTERACTIVE);
            if (!isInteractive && rsp.askForSave()) {
                fatal("you must specify '--save', '--no-save' or '--vanilla'");
            }
            if (embedded) {
                /*
                 * If we are in embedded mode, the creation of ConsoleReader and the ConsoleHandler
                 * should be lazy, as these may not be necessary and can cause hangs if stdin has
                 * been redirected.
                 */
                throw fatal("embedded mode disabled");
                // consoleHandler = new EmbeddedConsoleHandler(rsp, engine);
            } else {
                boolean useReadLine = !rsp.noReadline();
                if (useReadLine) {
                    return new JLineConsoleHandler(inStream, outStream, rsp.isSlave());
                } else {
                    return new DefaultConsoleHandler(inStream, outStream);
                }
            }
        }
    }

    /**
     * The standard R script escapes spaces to "~+~" in "-e" and "-f" commands.
     */
    static String unescapeSpace(String input) {
        return input.replace("~+~", " ");
    }

    private static final String GET_ECHO = "invisible(getOption('echo'))";
    private static final String QUIT_EOF = "quit(\"default\", 0L, TRUE)";
    private static final String GET_PROMPT = "invisible(getOption('prompt'))";
    private static final String GET_CONTINUE_PROMPT = "invisible(getOption('continue'))";

    /**
     * The read-eval-print loop, which can take input from a console, command line expression or a
     * file. There are two ways the repl can terminate:
     * <ol>
     * <li>A {@code quit} command is executed successfully.</li>
     * <li>EOF on the input.</li>
     * </ol>
     * In case 2, we must implicitly execute a {@code quit("default, 0L, TRUE} command before
     * exiting. So,in either case, we never return.
     */
    public static int readEvalPrint(Context context, ConsoleHandler consoleHandler) {
        int lastStatus = 0;
        try {
            while (true) { // processing inputs
                boolean doEcho = doEcho(context);
                consoleHandler.setPrompt(doEcho ? getPrompt(context) : "");
                try {
                    String input = consoleHandler.readLine();
                    if (input == null) {
                        throw new EOFException();
                    }
                    String trInput = input.trim();
                    if (trInput.equals("") || trInput.charAt(0) == '#') {
                        // nothing to parse
                        continue;
                    }

                    String continuePrompt = null;
                    StringBuilder sb = new StringBuilder(input);
                    while (true) { // processing subsequent lines while input is incomplete
                        lastStatus = 0;
                        try {
                            context.eval(Source.newBuilder("R", sb.toString(), "<REPL>").interactive(true).buildLiteral());
                        } catch (PolyglotException e) {
                            if (continuePrompt == null) {
                                continuePrompt = doEcho ? getContinuePrompt(context) : "";
                            }
                            if (e.isIncompleteSource()) {
                                // read another line of input
                                consoleHandler.setPrompt(continuePrompt);
                                String additionalInput = consoleHandler.readLine();
                                if (additionalInput == null) {
                                    throw new EOFException();
                                }
                                sb.append('\n');
                                sb.append(additionalInput);
                                // The only continuation in the while loop
                                continue;
                            } else if (e.isExit()) {
                                // usually from quit
                                throw new ExitException(e.getExitStatus());
                            } else if (e.isHostException()) {
                                // we continue the repl even though the system may be broken
                                lastStatus = 1;
                            } else if (e.isGuestException()) {
                                // drop through to continue REPL and remember last eval was an error
                                lastStatus = 1;
                            }
                        }
                        break;
                    }
                } catch (UserInterruptException e) {
                    // interrupted by ctrl-c
                }
            }
        } catch (EOFException e) {
            try {
                context.eval("R", QUIT_EOF);
            } catch (PolyglotException e2) {
                if (e2.isExit()) {
                    return e2.getExitStatus();
                }
                throw fatal(e, "error while calling quit");
            }
        } catch (ExitException e) {
            return e.code;
        }
        return lastStatus;
    }

    @SuppressWarnings("serial")
    private static final class ExitException extends RuntimeException {
        private final int code;

        ExitException(int code) {
            this.code = code;
        }
    }

    private static boolean doEcho(Context context) {
        try {
            return context.eval("R", GET_ECHO).asBoolean();
        } catch (PolyglotException e) {
            if (e.isExit()) {
                throw new ExitException(e.getExitStatus());
            }
            throw fatal(e, "error while retrieving echo");
        }
    }

    private static String getPrompt(Context context) {
        try {
            return context.eval("R", GET_PROMPT).asString();
        } catch (PolyglotException e) {
            if (e.isExit()) {
                throw new ExitException(e.getExitStatus());
            }
            throw fatal(e, "error while retrieving prompt");
        }
    }

    private static String getContinuePrompt(Context context) {
        try {
            return context.eval("R", GET_CONTINUE_PROMPT).asString();
        } catch (PolyglotException e) {
            if (e.isExit()) {
                throw new ExitException(e.getExitStatus());
            }
            throw fatal(e, "error while retrieving continue prompt");
        }
    }
}