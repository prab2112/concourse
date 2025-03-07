/*
 * Copyright (c) 2013-2015 Cinchapi, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cinchapi.concourse.shell;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.text.MessageFormat.format;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyShell;
import groovy.lang.MissingMethodException;
import groovy.lang.Script;
import jline.TerminalFactory;
import jline.console.ConsoleReader;
import jline.console.UserInterruptException;
import jline.console.completer.StringsCompleter;
import jline.console.history.FileHistory;

import org.apache.thrift.transport.TTransportException;
import org.cinchapi.concourse.Tag;
import org.cinchapi.concourse.Concourse;
import org.cinchapi.concourse.Timestamp;
import org.cinchapi.concourse.config.ConcourseClientPreferences;
import org.cinchapi.concourse.lang.Criteria;
import org.cinchapi.concourse.lang.StartState;
import org.cinchapi.concourse.thrift.Operator;
import org.cinchapi.concourse.thrift.TParseException;
import org.cinchapi.concourse.thrift.TSecurityException;
import org.cinchapi.concourse.util.FileOps;
import org.cinchapi.concourse.util.Version;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * The main program runner for the ConcourseShell client. ConcourseShell wraps a
 * connection to a ConcourseServer inside of a {@link GroovyShell}, which allows
 * for rich interaction with Concourse in a scripting environment.
 * 
 * @author Jeff Nelson
 */
public final class ConcourseShell {

    /**
     * Run the program...
     * 
     * @param args
     * @throws Exception
     */
    public static void main(String... args) throws Exception {
        try {
            ConcourseShell cash = new ConcourseShell();
            Options opts = new Options();
            JCommander parser = null;
            try {
                parser = new JCommander(opts, args);
            }
            catch (Exception e) {
                die(e.getMessage());
            }
            parser.setProgramName("concourse-shell");
            if(opts.help) {
                parser.usage();
                System.exit(1);
            }
            if(Strings.isNullOrEmpty(opts.password)) {
                cash.setExpandEvents(false);
                opts.password = cash.console.readLine("Password ["
                        + opts.username + "]: ", '*');
            }
            try {
                cash.concourse = Concourse.connect(opts.host, opts.port,
                        opts.username, opts.password, opts.environment);
                cash.whoami = opts.username;
            }
            catch (Exception e) {
                if(e.getCause() instanceof TTransportException) {
                    die("Unable to connect to the Concourse Server at "
                            + opts.host + ":" + opts.port);
                }
                else if(e.getCause() instanceof TSecurityException) {
                    die("Invalid username/password combination.");
                }
                else {
                    die(e.getMessage());
                }
            }
            if(!opts.ignoreRunCommands) {
                cash.loadExternalScript(opts.ext);
            }
            if(!Strings.isNullOrEmpty(opts.run)) {
                try {
                    String result = cash.evaluate(opts.run);
                    System.out.println(result);
                    cash.concourse.exit();
                    System.exit(0);
                }
                catch (IrregularEvaluationResult e) {
                    die(e.getMessage());
                }
            }
            else {
                cash.enableInteractiveSettings();
                boolean running = true;
                String input = "";
                while (running) {
                    boolean extraLineBreak = true;
                    boolean clearInput = true;
                    boolean clearPrompt = false;
                    try {
                        input = input + cash.console.readLine().trim();
                        String result = cash.evaluate(input);
                        System.out.println(result);
                    }
                    catch (UserInterruptException e) {
                        if(Strings.isNullOrEmpty(e.getPartialLine())
                                && Strings.isNullOrEmpty(input)) {
                            cash.console.println("Type EXIT to quit.");
                        }
                    }
                    catch (HelpRequest e) {
                        String text = getHelpText(e.topic);
                        if(!Strings.isNullOrEmpty(text)) {
                            Process p = Runtime.getRuntime().exec(
                                    new String[] {
                                            "sh",
                                            "-c",
                                            "echo \"" + text
                                                    + "\" | less > /dev/tty" });
                            p.waitFor();
                        }
                        cash.console.getHistory().removeLast();
                    }
                    catch (ExitRequest e) {
                        running = false;
                        cash.console.getHistory().removeLast();
                    }
                    catch (NewLineRequest e) {
                        extraLineBreak = false;
                    }
                    catch (ProgramCrash e) {
                        die(e.getMessage());
                    }
                    catch (MultiLineRequest e) {
                        extraLineBreak = false;
                        clearInput = false;
                        clearPrompt = true;
                    }
                    catch (IrregularEvaluationResult e) {
                        System.err.println(e.getMessage());
                    }
                    finally {
                        if(extraLineBreak) {
                            cash.console.print("\n");
                        }
                        if(clearInput) {
                            input = "";
                        }
                        if(clearPrompt) {
                            cash.console.setPrompt("> ");
                        }
                        else {
                            cash.console.setPrompt(cash.defaultPrompt);
                        }
                    }
                }
                cash.concourse.exit();
                System.exit(0);
            }
        }
        finally {
            TerminalFactory.get().restore();
        }
    }

    /**
     * Return a sorted array that contains all the accessible API methods.
     * 
     * @return the accessible API methods
     */
    protected static String[] getAccessibleApiMethods() {
        if(ACCESSIBLE_API_METHODS == null) {
            Set<String> banned = Sets.newHashSet("equals", "getClass",
                    "hashCode", "notify", "notifyAll", "toString", "wait",
                    "exit");
            Set<String> methods = Sets.newTreeSet();
            for (Method method : Concourse.class.getMethods()) {
                if(!Modifier.isStatic(method.getModifiers())
                        && !banned.contains(method.getName())) {
                    // NOTE: Even though the StringCompleter strips the
                    // "concourse." from these method names, we must add it here
                    // so that we can properly handle short syntax in
                    // SyntaxTools#handleShortSyntax
                    methods.add(format("concourse.{0}", method.getName()));
                }
            }
            ACCESSIBLE_API_METHODS = methods
                    .toArray(new String[methods.size()]);
        }
        return ACCESSIBLE_API_METHODS;

    }

    /**
     * Return {@code true} if {@code string} contains at last one of the
     * {@link #BANNED_CHAR_SEQUENCES} strings.
     * 
     * @param string
     * @return {@code true} if string contains a banned character sequence
     */
    private static boolean containsBannedCharSequence(String string) {
        for (String charSequence : BANNED_CHAR_SEQUENCES) {
            if(string.contains(charSequence)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Print {@code message} to stderr and exit with a non-zero status.
     * 
     * @param message
     */
    private static void die(String message) {
        System.err.println("ERROR: " + message);
        System.exit(1);
    }

    /**
     * Return a sorted array that contains all the accessible API methods using
     * short syntax.
     * 
     * @return the accessible API methods using short syntax
     */
    private static String[] getAccessibleApiMethodsUsingShortSyntax() {
        Set<String> methods = Sets.newTreeSet();
        for (String method : getAccessibleApiMethods()) {
            methods.add(method.replace("concourse.", ""));
        }
        // Add the Showable items
        for (Showable showable : Showable.values()) {
            methods.add("show " + showable.getName());
        }
        return methods.toArray(new String[methods.size()]);
    }

    /**
     * Return the help text for a given {@code topic}.
     * 
     * @param topic
     * @return the help text
     */
    private static String getHelpText(String topic) {
        topic = Strings.isNullOrEmpty(topic) ? "man" : topic;
        topic = topic.toLowerCase();
        InputStream in = ConcourseShell.class.getResourceAsStream("/" + topic);
        if(in == null) {
            System.err.println("No help entry for " + topic);
            return null;
        }
        else {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(in));
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.replaceAll("\"", "\\\\\"");
                    builder.append(line).append(
                            System.getProperty("line.separator"));
                }
                String text = builder.toString().trim();
                reader.close();
                return text;
            }
            catch (IOException e) {
                throw Throwables.propagate(e);
            }
        }
    }

    /**
     * A cache of the API methods that are accessible in CaSH.
     */
    private static String[] ACCESSIBLE_API_METHODS = null;

    /**
     * A list of char sequences that we must ban for security and other
     * miscellaneous purposes.
     */
    private static List<String> BANNED_CHAR_SEQUENCES = Lists.newArrayList(
            "concourse.exit()", "concourse.username", "concourse.password",
            "concourse.client");

    /**
     * A list which contains all of the accessible API methods. This list is
     * used to expand short syntax that is used in any evaluatable line.
     */
    private static final List<String> methods = Lists
            .newArrayList(getAccessibleApiMethods());

    /**
     * The name of the external script that is
     * {@link #loadExternalScript(String)
     * loaded}. This name is how the script functions are stored in the
     * {@link #groovyBinding}.
     */
    private static final String EXTERNAL_SCRIPT_NAME = "ext";

    /**
     * A closure that converts a string value to a tag.
     */
    private static Closure<Tag> STRING_TO_TAG = new Closure<Tag>(null) {

        private static final long serialVersionUID = 1L;

        @Override
        public Tag call(Object arg) {
            return Tag.create(arg.toString());
        }

    };

    /**
     * A closure that returns a nwe CriteriaBuilder object.
     */
    private static Closure<StartState> WHERE = new Closure<StartState>(null) {

        private static final long serialVersionUID = 1L;

        @Override
        public StartState call() {
            return Criteria.where();
        }

    };

    /**
     * The client connection to Concourse.
     */
    protected Concourse concourse;

    /**
     * The file where the user's CASH history is stored.
     */
    protected String historyStore = System.getProperty("user.home")
            + File.separator + ".cash_history";

    /**
     * The Concourse user that is currently connected to the shell.
     */
    protected String whoami;

    /**
     * The env that the client is connected to.
     */
    protected String env;

    /**
     * The console handles all I/O.
     */
    private ConsoleReader console;

    /**
     * The groovy environment that actually evaluates the user input.
     */
    private GroovyShell groovy;

    /**
     * The binding that contains all the variables that are in scope for the
     * groovy environment.
     */
    private Binding groovyBinding;

    /**
     * The stopwatch that is used to time the duration of all evaluations.
     */
    private Stopwatch watch = Stopwatch.createUnstarted();

    /**
     * The shell prompt.
     */
    private String defaultPrompt;

    /**
     * An external script that has been loaded by the
     * {@link #loadExternalScript(String)} method.
     */
    private Script script = null;

    /**
     * A closure that responds to the 'show' command and returns information to
     * display to the user based on the input argument(s).
     */
    private final Closure<Object> showFunction = new Closure<Object>(null) {

        private static final long serialVersionUID = 1L;

        @Override
        public Object call(Object arg) {
            if(arg == Showable.RECORDS) {
                return concourse.inventory();
            }
            else {
                StringBuilder sb = new StringBuilder();
                sb.append("Unable to show ");
                sb.append(arg);
                sb.append(". Valid options are: ");
                sb.append(Showable.OPTIONS);
                throw new IllegalArgumentException(sb.toString());
            }
        }

    };

    /**
     * A closure that responds to time/date alias functions.
     */
    private final Closure<Timestamp> timeFunction = new Closure<Timestamp>(null) {

        private static final long serialVersionUID = 1L;

        @Override
        public Timestamp call(Object arg) {
            return concourse.time(arg.toString());
        }

        @Override
        public Timestamp call() {
            return concourse.time();
        }
    };

    /**
     * Construct a new instance. Be sure to call {@link #setClient(Concourse)}
     * before performing any
     * evaluations.
     * 
     * @throws Exception
     */
    protected ConcourseShell() throws Exception {
        this.console = new ConsoleReader();
        this.groovyBinding = new Binding();
        this.groovy = new GroovyShell(groovyBinding);
    }

    /**
     * Evaluate the given {@code input} and return the result that should be
     * displayed to the user. If, for some reason, the evaluation of the input
     * does not yield a displayable response, a subclass of
     * {@link IrregularEvaluationResult} will be thrown to give the caller and
     * indication of how to proceed.
     * 
     * @param input
     * @return the result of the evaluation
     * @throws IrregularEvaluationResult
     */
    public String evaluate(String input) throws IrregularEvaluationResult {
        input = SyntaxTools.handleShortSyntax(input, methods);
        String inputLowerCase = input.toLowerCase();

        // NOTE: These must always be set before evaluating a line just in case
        // an attempt was made to bind the variables to different values in a
        // previous evaluation.
        groovyBinding.setVariable("concourse", concourse);
        groovyBinding.setVariable("eq", Operator.EQUALS);
        groovyBinding.setVariable("ne", Operator.NOT_EQUALS);
        groovyBinding.setVariable("gt", Operator.GREATER_THAN);
        groovyBinding.setVariable("gte", Operator.GREATER_THAN_OR_EQUALS);
        groovyBinding.setVariable("lt", Operator.LESS_THAN);
        groovyBinding.setVariable("lte", Operator.LESS_THAN_OR_EQUALS);
        groovyBinding.setVariable("bw", Operator.BETWEEN);
        groovyBinding.setVariable("regex", Operator.REGEX);
        groovyBinding.setVariable("nregex", Operator.NOT_REGEX);
        groovyBinding.setVariable("lnk2", Operator.LINKS_TO);
        groovyBinding.setVariable("time", timeFunction);
        groovyBinding.setVariable("date", timeFunction);
        groovyBinding.setVariable("where", WHERE);
        groovyBinding.setVariable("tag", STRING_TO_TAG);
        groovyBinding.setVariable("whoami", whoami);
        // Add Showable variables
        for (Showable showable : Showable.values()) {
            groovyBinding.setVariable(showable.getName(), showable);
        }
        groovyBinding.setVariable("show", showFunction);
        if(script != null) {
            groovyBinding.setVariable(EXTERNAL_SCRIPT_NAME, script);
        }
        if(inputLowerCase.equalsIgnoreCase("exit")) {
            throw new ExitRequest();
        }
        else if(inputLowerCase.startsWith("help")
                || inputLowerCase.startsWith("man")) {
            String[] toks = input.split(" ");
            if(toks.length == 1) {
                throw new HelpRequest();
            }
            else {
                String topic = toks[1];
                throw new HelpRequest(topic);
            }
        }
        else if(containsBannedCharSequence(input)) {
            throw new EvaluationException("Cannot evaluate input because "
                    + "it contains an illegal character sequence");
        }
        else if(Strings.isNullOrEmpty(input)) { // CON-170
            throw new NewLineRequest();
        }
        else {
            StringBuilder result = new StringBuilder();
            try {
                watch.reset().start();
                Object value = groovy.evaluate(input, "ConcourseShell");
                watch.stop();
                long elapsed = watch.elapsed(TimeUnit.MILLISECONDS);
                double seconds = elapsed / 1000.0;
                if(value != null) {
                    result.append("Returned '" + value + "' in " + seconds
                            + " sec");
                }
                else {
                    result.append("Completed in " + seconds + " sec");
                }
                return result.toString();
            }
            catch (Exception e) {
                if(e.getCause() instanceof TTransportException) {
                    throw new ProgramCrash(e.getMessage());
                }
                else if(e.getCause() instanceof TSecurityException) {
                    throw new ProgramCrash(
                            "A security change has occurred and your "
                                    + "session cannot continue");
                }
                else if(e instanceof CompilationFailedException) {
                    throw new MultiLineRequest(e.getMessage());
                }
                else if(e instanceof MissingMethodException
                        && script != null
                        && e.getMessage().contains(
                                "No signature of method: ConcourseShell.")) {
                    String method = e.getMessage().split("ConcourseShell.")[1]
                            .split("\\(")[0];
                    input = input.replaceAll(method, "ext." + method);
                    return evaluate(input);
                }
                else {
                    String message = e.getCause() instanceof TParseException ? e
                            .getCause().getMessage() : e.getMessage();
                    throw new EvaluationException("ERROR: " + message);
                }
            }
        }
    }

    /**
     * Load an external script and store it so it can be added to the binding
     * when {@link #evaluate(String) evaluating} commands. Any functions defined
     * in the script must be accessed using the {@code ext} qualifier.
     * 
     * @param script
     */
    public void loadExternalScript(String script) {
        try {
            Path extPath = Paths.get(script);
            if(Files.exists(extPath) && Files.size(extPath) > 0) {
                List<String> lines = FileOps.readLines(script);
                StringBuilder sb = new StringBuilder();
                for (String line : lines) {
                    line = SyntaxTools.handleShortSyntax(line, methods);
                    sb.append(line)
                            .append(System.getProperty("line.separator"));
                }
                String scriptText = sb.toString();
                try {
                    this.script = groovy
                            .parse(scriptText, EXTERNAL_SCRIPT_NAME);
                    evaluate(scriptText);
                }
                catch (IrregularEvaluationResult e) {
                    System.err.println(e.getMessage());
                }
                catch (MultipleCompilationErrorsException e) {
                    String msg = e.getMessage();
                    msg = msg.substring(msg.indexOf('\n') + 1);
                    msg = msg.replaceAll("ext: ", "");
                    die("A fatal error occurred while parsing the run-commands file at "
                            + script
                            + System.getProperty("line.separator")
                            + msg
                            + System.getProperty("line.separator")
                            + "Fix these errors or start concourse shell with the --no-run-commands flag");
                }
            }
        }
        catch (IOException e) {
            throw Throwables.propagate(e);
        }
    }

    /**
     * This method calls {@link ConsoleReader#setExpandEvents(boolean)} with the
     * specified value.
     * 
     * @param bool
     */
    public void setExpandEvents(boolean bool) {
        console.setExpandEvents(bool);
    }

    /**
     * Turn on the settings necessary to make the application "interactive"
     * (e.g. a REPL). By default, these settings are turned off.
     */
    private void enableInteractiveSettings() throws Exception {
        console.setExpandEvents(false);
        console.setHandleUserInterrupt(true);
        File file = new File(historyStore);
        file.createNewFile();
        console.setHistory(new FileHistory(file));
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    ((FileHistory) console.getHistory()).flush();
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }

        }));
        CommandLine.displayWelcomeBanner();
        env = concourse.getServerEnvironment();
        setDefaultPrompt();
        console.println("Client Version "
                + Version.getVersion(ConcourseShell.class));
        console.println("Server Version " + concourse.getServerVersion());
        console.println("");
        console.println("Connected to the '" + env + "' environment.");
        console.println("");
        console.println("Type HELP for help.");
        console.println("Type EXIT to quit.");
        console.println("Use TAB for completion.");
        console.println("");
        console.setPrompt(defaultPrompt);
        console.addCompleter(new StringsCompleter(
                getAccessibleApiMethodsUsingShortSyntax()));
    }

    /**
     * Set the {@link #defaultPrompt} variable to account for the current
     * {@link #env}.
     */
    private void setDefaultPrompt() {
        this.defaultPrompt = format("[{0}/cash]$ ", env);
    }

    /**
     * The options that can be passed to the main method of this script.
     * 
     * @author Jeff Nelson
     */
    private static class Options {

        /**
         * A handler for the client preferences that <em>may</em> exist in the
         * user's home directory.
         */
        private ConcourseClientPreferences prefs = null;

        {
            String file = System.getProperty("user.home") + File.separator
                    + "concourse_client.prefs";
            if(Files.exists(Paths.get(file))) { // check to make sure that the
                                                // file exists first, so we
                                                // don't create a blank one if
                                                // it doesn't
                prefs = ConcourseClientPreferences.load(file);
            }
        }

        @Parameter(names = { "-e", "--environment" }, description = "The environment of the Concourse Server to use")
        public String environment = prefs != null ? prefs.getEnvironment() : "";

        @Parameter(names = "--help", help = true, hidden = true)
        public boolean help;

        @Parameter(names = { "-h", "--host" }, description = "The hostname where the Concourse Server is located")
        public String host = prefs != null ? prefs.getHost() : "localhost";

        @Parameter(names = "--password", description = "The password", password = false, hidden = true)
        public String password = prefs != null ? new String(prefs.getPassword())
                : null;

        @Parameter(names = { "-p", "--port" }, description = "The port on which the Concourse Server is listening")
        public int port = prefs != null ? prefs.getPort() : 1717;

        @Parameter(names = { "-r", "--run" }, description = "The command to run non-interactively")
        public String run = "";

        @Parameter(names = { "-u", "--username" }, description = "The username with which to connect")
        public String username = prefs != null ? prefs.getUsername() : "admin";

        @Parameter(names = { "--run-commands", "--rc" }, description = "Path to a script that contains commands to run when the shell starts")
        public String ext = FileOps.getUserHome() + "/.cashrc";

        @Parameter(names = { "--no-run-commands", "--no-rc" }, description = "A flag to disable loading any run commands file")
        public boolean ignoreRunCommands = false;

    }

    /**
     * An enum containing the types of things that can be listed using the
     * 'show' function.
     * 
     * @author Jeff Nelson
     */
    private enum Showable {
        RECORDS;

        /**
         * Return the name of this Showable.
         * 
         * @return the name
         */
        public String getName() {
            return name().toLowerCase();
        }

        /**
         * Valid options for the 'show' function based on the values defined in
         * this enum.
         */
        private static String OPTIONS;
        static {
            StringBuilder sb = new StringBuilder();
            for (Showable showable : values()) {
                sb.append(showable.toString().toLowerCase()).append(" ");
            }
            sb.deleteCharAt(sb.length() - 1);
            OPTIONS = sb.toString();
        }

    }

}
