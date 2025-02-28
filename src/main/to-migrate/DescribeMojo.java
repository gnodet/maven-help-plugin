/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.help;

import org.apache.maven.api.Lifecycle;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.descriptor.MojoDescriptor;
import org.apache.maven.api.plugin.descriptor.Parameter;
import org.apache.maven.api.plugin.descriptor.PluginDescriptor;
import org.apache.maven.api.services.MessageBuilder;
import org.apache.maven.api.services.MessageBuilderFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * Displays a list of the attributes for a Maven Plugin and/or goals (aka Mojo - Maven plain Old Java Object).
 *
 * @see <a href="http://maven.apache.org/general.html#What_is_a_Mojo">What is a Mojo?</a>
 * @since 2.0
 */
@Mojo(name = "describe", projectRequired = false, aggregator = true)
public class DescribeMojo extends AbstractHelpMojo {
    /**
     * The default indent size when writing description's Mojo.
     */
    private static final int INDENT_SIZE = 2;

    /**
     * For unknown values
     */
    private static final String UNKNOWN = "Unknown";

    /**
     * For not defined values
     */
    private static final String NOT_DEFINED = "Not defined";

    /**
     * For deprecated values
     */
    private static final String NO_REASON = "No reason given";

    private static final Pattern EXPRESSION = Pattern.compile("^\\$\\{([^}]+)\\}$");

    // ----------------------------------------------------------------------
    // Mojo components
    // ----------------------------------------------------------------------
    
    @Inject
    Session session;
    
    @Inject
    MessageBuilderFactory messageBuilderFactory;
    
    // ----------------------------------------------------------------------
    // Mojo parameters
    // ----------------------------------------------------------------------

    /**
     * The Maven Plugin to describe. This must be specified in one of three ways:
     * <br/>
     * <ol>
     * <li>plugin-prefix, i.e. 'help'</li>
     * <li>groupId:artifactId, i.e. 'org.apache.maven.plugins:maven-help-plugin'</li>
     * <li>groupId:artifactId:version, i.e. 'org.apache.maven.plugins:maven-help-plugin:2.0'</li>
     * </ol>
     */
    @org.apache.maven.api.plugin.annotations.Parameter(property = "plugin", alias = "prefix")
    private String plugin;

    /**
     * The Maven Plugin <code>groupId</code> to describe.
     * <br/>
     * <b>Note</b>: Should be used with <code>artifactId</code> parameter.
     */
    @org.apache.maven.api.plugin.annotations.Parameter(property = "groupId")
    private String groupId;

    /**
     * The Maven Plugin <code>artifactId</code> to describe.
     * <br/>
     * <b>Note</b>: Should be used with <code>groupId</code> parameter.
     */
    @org.apache.maven.api.plugin.annotations.Parameter(property = "artifactId")
    private String artifactId;

    /**
     * The Maven Plugin <code>version</code> to describe.
     * <br/>
     * <b>Note</b>: Should be used with <code>groupId/artifactId</code> parameters.
     */
    @org.apache.maven.api.plugin.annotations.Parameter(property = "version")
    private String version;

    /**
     * The goal name of a Mojo to describe within the specified Maven Plugin.
     * If this parameter is specified, only the corresponding goal (Mojo) will be described,
     * rather than the whole Plugin.
     *
     * @since 2.1
     */
    @org.apache.maven.api.plugin.annotations.Parameter(property = "goal")
    private String goal;

    /**
     * This flag specifies that a detailed (verbose) list of goal (Mojo) information should be given.
     *
     * @since 2.1
     */
    @org.apache.maven.api.plugin.annotations.Parameter(property = "detail", defaultValue = "false")
    private boolean detail;

    /**
     * This flag specifies that a minimal list of goal (Mojo) information should be given.
     *
     * @since 2.1
     */
    @org.apache.maven.api.plugin.annotations.Parameter(property = "minimal", defaultValue = "false")
    private boolean minimal;

    /**
     * A Maven command like a single goal or a single phase following the Maven command line:
     * <br/>
     * <code>mvn [options] [&lt;goal(s)&gt;] [&lt;phase(s)&gt;]</code>
     *
     * @since 2.1
     */
    @org.apache.maven.api.plugin.annotations.Parameter(property = "cmd")
    private String cmd;

    // ----------------------------------------------------------------------
    // Public methods
    // ----------------------------------------------------------------------

    /**
     * {@inheritDoc}
     */
    public void execute() throws MojoException {
        StringBuilder descriptionBuffer = new StringBuilder();

        boolean describePlugin = true;
        if (cmd != null && !cmd.isEmpty()) {
            describePlugin = describeCommand(descriptionBuffer);
        }

        if (describePlugin) {
            PluginInfo pi = parsePluginLookupInfo();
            PluginDescriptor descriptor = lookupPluginDescriptor(pi);
            if (goal != null && !goal.isEmpty()) {
                MojoDescriptor mojo = descriptor.getMojos()
                        .stream().filter(m -> goal.equals(m.getGoal())).findAny().orElse(null);
                if (mojo == null) {
                    throw new MojoException(
                            "The goal '" + goal + "' does not exist in the plugin '" + pi.getPrefix() + "'");
                }
                describeMojo(mojo, descriptionBuffer);
            } else {
                describePlugin(descriptor, descriptionBuffer);
            }
        }

        writeDescription(descriptionBuffer);
    }

    // ----------------------------------------------------------------------
    // Private methods
    // ----------------------------------------------------------------------

    /**
     * Method to write the Mojo description into the output file
     *
     * @param descriptionBuffer contains the description to be written to the file
     * @throws MojoException if any
     */
    private void writeDescription(StringBuilder descriptionBuffer) throws MojoException {
        if (output != null) {
            try {
                writeFile(output, descriptionBuffer);
            } catch (IOException e) {
                throw new MojoException("Cannot write plugin/goal description to output: " + output, e);
            }

            getLog().info("Wrote descriptions to: " + output);
        } else {
            getLog().info(descriptionBuffer.toString());
        }
    }

    /**
     * Method for retrieving the description of the plugin
     *
     * @param pi holds information of the plugin whose description is to be retrieved
     * @return a PluginDescriptor where the plugin description is to be retrieved
     * @throws MojoException if the plugin could not be verify
     * @throws MojoException   if groupId or artifactId is empty
     */
    private PluginDescriptor lookupPluginDescriptor(PluginInfo pi) throws MojoException {
        Plugin forLookup = null;
        if (pi.getPrefix() != null && !pi.getPrefix().isEmpty()) {
            try {
                forLookup = mojoDescriptorCreator.findPluginForPrefix(pi.getPrefix(), session);
            } catch (NoPluginFoundForPrefixException e) {
                throw new MojoException("Unable to find the plugin with prefix: " + pi.getPrefix(), e);
            }
        } else if (StringUtils.isNotEmpty(pi.getGroupId()) && StringUtils.isNotEmpty(pi.getArtifactId())) {
            forLookup = new Plugin();
            forLookup.setGroupId(pi.getGroupId());
            forLookup.setArtifactId(pi.getArtifactId());
        }
        if (forLookup == null) {
            String msg = "You must specify either: both 'groupId' and 'artifactId' parameters OR a 'plugin' parameter"
                    + " OR a 'cmd' parameter. For instance:" + LS
                    + "  # mvn help:describe -Dcmd=install" + LS
                    + "or" + LS
                    + "  # mvn help:describe -Dcmd=help:describe" + LS
                    + "or" + LS
                    + "  # mvn help:describe -Dplugin=org.apache.maven.plugins:maven-help-plugin" + LS
                    + "or" + LS
                    + "  # mvn help:describe -DgroupId=org.apache.maven.plugins -DartifactId=maven-help-plugin" + LS
                    + LS
                    + "Try 'mvn help:help -Ddetail=true' for more information.";
            throw new MojoException(msg);
        }

        if (StringUtils.isNotEmpty(pi.getVersion())) {
            forLookup.setVersion(pi.getVersion());
        } else {
            try {
                DefaultPluginVersionRequest versionRequest = new DefaultPluginVersionRequest(forLookup, session);
                versionRequest.setPom(project.getModel());
                PluginVersionResult versionResult = pluginVersionResolver.resolve(versionRequest);
                forLookup.setVersion(versionResult.getVersion());
            } catch (PluginVersionResolutionException e) {
                throw new MojoException(
                        "Unable to resolve the version of the plugin with prefix: " + pi.getPrefix(), e);
            }
        }

        try {
            return pluginManager.getPluginDescriptor(
                    forLookup, project.getRemotePluginRepositories(), session.getRepositorySession());
        } catch (Exception e) {
            throw new MojoException(
                    "Error retrieving plugin descriptor for:" + LS + LS + "groupId: '"
                            + groupId + "'" + LS + "artifactId: '" + artifactId + "'" + LS + "version: '" + version
                            + "'" + LS
                            + LS,
                    e);
        }
    }

    /**
     * Method for parsing the plugin parameter
     *
     * @return Plugin info containing information about the plugin whose description is to be retrieved
     * @throws MojoException if <code>plugin<*code> parameter is not conform to
     *                              <code>groupId:artifactId[:version]</code>
     */
    private PluginInfo parsePluginLookupInfo() throws MojoException {
        PluginInfo pi = new PluginInfo();
        if (plugin != null && !plugin.isEmpty()) {
            if (plugin.indexOf(':') > -1) {
                String[] pluginParts = plugin.split(":");

                switch (pluginParts.length) {
                    case 1:
                        pi.setPrefix(pluginParts[0]);
                        break;
                    case 2:
                        pi.setGroupId(pluginParts[0]);
                        pi.setArtifactId(pluginParts[1]);
                        break;
                    case 3:
                        pi.setGroupId(pluginParts[0]);
                        pi.setArtifactId(pluginParts[1]);
                        pi.setVersion(pluginParts[2]);
                        break;
                    default:
                        throw new MojoException("plugin parameter must be a plugin prefix,"
                                + " or conform to: 'groupId:artifactId[:version]'.");
                }
            } else {
                pi.setPrefix(plugin);
            }
        } else {
            pi.setGroupId(groupId);
            pi.setArtifactId(artifactId);
            pi.setVersion(version);
        }
        return pi;
    }

    /**
     * Method for retrieving the plugin description
     *
     * @param pd     contains the plugin description
     * @param buffer contains the information to be displayed or printed
     * @throws MojoException   if any reflection exceptions occur.
     * @throws MojoException if any
     */
    private void describePlugin(PluginDescriptor pd, StringBuilder buffer)
            throws MojoException, MojoException {
        append(buffer, pd.getId(), 0);
        buffer.append(LS);

        String name = pd.getName();
        if (name == null) {
            // Can be null because of MPLUGIN-137 (and descriptors generated with maven-plugin-tools-api <= 2.4.3)
            Artifact aetherArtifact = new DefaultArtifact(pd.getGroupId(), pd.getArtifactId(), "jar", pd.getVersion());
            ProjectBuildingRequest pbr = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
            pbr.setRemoteRepositories(project.getRemoteArtifactRepositories());
            pbr.setPluginArtifactRepositories(project.getPluginArtifactRepositories());
            pbr.setProject(null);
            pbr.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
            try {
                Artifact artifactCopy = resolveArtifact(aetherArtifact).getArtifact();
                name = projectBuilder
                        .build(RepositoryUtils.toArtifact(artifactCopy), pbr)
                        .getProject()
                        .getName();
            } catch (Exception e) {
                // oh well, we tried our best.
                getLog().warn("Unable to get the name of the plugin " + pd.getId() + ": " + e.getMessage());
                name = pd.getId();
            }
        }
        append(buffer, "Name", buffer().strong(name).toString(), 0);
        appendAsParagraph(buffer, "Description", toDescription(pd.getDescription()), 0);
        append(buffer, "Group Id", pd.getGroupId(), 0);
        append(buffer, "Artifact Id", pd.getArtifactId(), 0);
        append(buffer, "Version", pd.getVersion(), 0);
        append(
                buffer,
                "Goal Prefix",
                buffer().strong(pd.getGoalPrefix()).toString(),
                0);
        buffer.append(LS);

        List<MojoDescriptor> mojos = pd.getMojos();

        if (mojos == null) {
            append(buffer, "This plugin has no goals.", 0);
            return;
        }

        if (!minimal) {
            append(buffer, "This plugin has " + mojos.size() + " goal" + (mojos.size() > 1 ? "s" : "") + ":", 0);
            buffer.append(LS);

            mojos = mojos.stream()
                    .sorted((m1, m2) -> m1.getGoal().compareToIgnoreCase(m2.getGoal()))
                    .collect(Collectors.toList());

            for (MojoDescriptor md : mojos) {
                describeMojoGuts(md, buffer, detail);
                buffer.append(LS);
            }
        }

        if (!detail) {
            buffer.append("For more information, run 'mvn help:describe [...] -Ddetail'");
            buffer.append(LS);
        }
    }

    /**
     * Displays information about the Plugin Mojo
     *
     * @param md     contains the description of the Plugin Mojo
     * @param buffer the displayed output
     * @throws MojoException   if any reflection exceptions occur.
     * @throws MojoException if any
     */
    private void describeMojo(MojoDescriptor md, StringBuilder buffer)
            throws MojoException, MojoException {
        buffer.append("Mojo: '").append(md.getFullGoalName()).append("'");
        buffer.append(LS);

        describeMojoGuts(md, buffer, detail);
        buffer.append(LS);

        if (!detail) {
            buffer.append("For more information, run 'mvn help:describe [...] -Ddetail'");
            buffer.append(LS);
        }
    }

    /**
     * Displays detailed information about the Plugin Mojo
     *
     * @param md              contains the description of the Plugin Mojo
     * @param buffer          contains information to be printed or displayed
     * @param fullDescription specifies whether all the details about the Plugin Mojo is to  be displayed
     * @throws MojoException   if any reflection exceptions occur.
     * @throws MojoException if any
     */
    private void describeMojoGuts(MojoDescriptor md, StringBuilder buffer, boolean fullDescription)
            throws MojoException, MojoException {
        append(buffer, buffer().strong(md.getFullGoalName()).toString(), 0);

        // indent 1
        appendAsParagraph(buffer, "Description", toDescription(md.getDescription()), 1);

        String deprecation = md.getDeprecated();
        if (deprecation != null && deprecation.length() <= 0) {
            deprecation = NO_REASON;
        }

        if (deprecation != null && !deprecation.isEmpty()) {
            append(
                    buffer,
                    buffer().warning("Deprecated. " + deprecation).toString(),
                    1);
        }

        if (isReportGoal(md)) {
            append(buffer, "Note", "This goal should be used as a Maven report.", 1);
        }

        if (!fullDescription) {
            return;
        }

        append(buffer, "Implementation", md.getImplementation(), 1);
        append(buffer, "Language", md.getLanguage(), 1);

        String phase = md.getPhase();
        if (phase != null && !phase.isEmpty()) {
            append(buffer, "Bound to phase", phase, 1);
        }

        String eGoal = md.getExecuteGoal();
        String eLife = md.getExecuteLifecycle();
        String ePhase = md.getExecutePhase();

        if ((eGoal != null && !eGoal.isEmpty()) || (ePhase != null && !ePhase.isEmpty())) {
            append(buffer, "Before this goal executes, it will call:", 1);

            if (eGoal != null && !eGoal.isEmpty()) {
                append(buffer, "Single goal", "'" + eGoal + "'", 2);
            }

            if (ePhase != null && !ePhase.isEmpty()) {
                String s = "Phase: '" + ePhase + "'";

                if (eLife != null && !eLife.isEmpty()) {
                    s += " in Lifecycle Overlay: '" + eLife + "'";
                }

                append(buffer, s, 2);
            }
        }

        buffer.append(LS);

        describeMojoParameters(md, buffer);
    }

    /**
     * Displays parameter information of the Plugin Mojo
     *
     * @param md     contains the description of the Plugin Mojo
     * @param buffer contains information to be printed or displayed
     * @throws MojoException   if any reflection exceptions occur.
     * @throws MojoException if any
     */
    private void describeMojoParameters(MojoDescriptor md, StringBuilder buffer)
            throws MojoException, MojoException {
        List<Parameter> params = md.getParameters();

        if (params == null || params.isEmpty()) {
            append(buffer, "This mojo doesn't use any parameters.", 1);
            return;
        }

        params = params.stream()
                .sorted((p1, p2) -> p1.getName().compareToIgnoreCase(p2.getName()))
                .toList();

        append(buffer, "Available parameters:", 1);

        // indent 2
        for (Parameter parameter : params) {
            if (!parameter.isEditable()) {
                continue;
            }

            buffer.append(LS);

            // DGF wouldn't it be nice if this worked?
            String defaultVal = parameter.getDefaultValue();
            if (defaultVal == null) {
                // defaultVal is ALWAYS null, this is a bug in PluginDescriptorBuilder (cf. MNG-4941)
                defaultVal =
                        md.getMojoConfiguration().getChild(parameter.getName()).getAttribute("default-value", null);
            }

            if (defaultVal != null && !defaultVal.isEmpty()) {
                defaultVal = " (Default: " + buffer().strong(defaultVal) + ")";
            } else {
                defaultVal = "";
            }
            append(buffer, buffer().strong(parameter.getName()) + defaultVal, 2);

            String alias = parameter.getAlias();
            if (!(alias == null || alias.isEmpty())) {
                append(buffer, "Alias", alias, 3);
            }

            if (parameter.isRequired()) {
                append(buffer, "Required", "true", 3);
            }

            String expression = parameter.getExpression();
            if (expression == null || expression.isEmpty()) {
                // expression is ALWAYS null, this is a bug in PluginDescriptorBuilder (cf. MNG-4941).
                // Fixed with Maven-3.0.1
                expression =
                        md.getMojoConfiguration().getChild(parameter.getName()).getValue(null);
            }
            if (expression != null && !expression.isEmpty()) {
                Matcher matcher = EXPRESSION.matcher(expression);
                if (matcher.matches()) {
                    append(buffer, "User property", matcher.group(1), 3);
                } else {
                    append(buffer, "Expression", expression, 3);
                }
            }

            append(buffer, toDescription(parameter.getDescription()), 3);

            String deprecation = parameter.getDeprecated();
            if (deprecation != null && deprecation.length() <= 0) {
                deprecation = NO_REASON;
            }

            if (deprecation != null && !deprecation.isEmpty()) {
                append(
                        buffer,
                        buffer()
                                .warning("Deprecated. " + deprecation)
                                .toString(),
                        3);
            }
        }
    }

    /**
     * Describe the <code>cmd</code> parameter
     *
     * @param descriptionBuffer not null
     * @return <code>true</code> if it implies to describe a plugin, <code>false</code> otherwise.
     * @throws MojoException if any
     */
    private boolean describeCommand(StringBuilder descriptionBuffer) throws MojoException {
        if (cmd.indexOf(':') == -1) {
            // phase
            Lifecycle lifecycle = defaultLifecycles.getPhaseToLifecycleMap().get(cmd);
            if (lifecycle == null) {
                throw new MojoException("The given phase '" + cmd + "' is an unknown phase.");
            }

            Map<String, String> defaultLifecyclePhases = lifecycleMappings
                    .get(project.getPackaging())
                    .getLifecycles()
                    .get("default")
                    .getPhases();
            List<String> phases = lifecycle.getPhases();

            if (lifecycle.getDefaultPhases() == null) {
                descriptionBuffer.append("'").append(cmd);
                descriptionBuffer
                        .append("' is a phase corresponding to this plugin:")
                        .append(LS);
                for (String key : phases) {
                    if (!key.equals(cmd)) {
                        continue;
                    }
                    if (defaultLifecyclePhases.get(key) != null) {
                        descriptionBuffer.append(defaultLifecyclePhases.get(key));
                        descriptionBuffer.append(LS);
                    }
                }

                descriptionBuffer.append(LS);
                descriptionBuffer.append("It is a part of the lifecycle for the POM packaging '");
                descriptionBuffer.append(project.getPackaging());
                descriptionBuffer.append("'. This lifecycle includes the following phases:");
                descriptionBuffer.append(LS);
                for (String key : phases) {
                    descriptionBuffer.append("* ").append(key).append(": ");
                    String value = defaultLifecyclePhases.get(key);
                    if (value != null && !value.isEmpty()) {
                        for (StringTokenizer tok = new StringTokenizer(value, ","); tok.hasMoreTokens(); ) {
                            descriptionBuffer.append(tok.nextToken().trim());

                            if (!tok.hasMoreTokens()) {
                                descriptionBuffer.append(LS);
                            } else {
                                descriptionBuffer.append(", ");
                            }
                        }
                    } else {
                        descriptionBuffer.append(NOT_DEFINED).append(LS);
                    }
                }
            } else {
                descriptionBuffer.append("'").append(cmd);
                descriptionBuffer.append("' is a phase within the '").append(lifecycle.getId());
                descriptionBuffer.append("' lifecycle, which has the following phases: ");
                descriptionBuffer.append(LS);

                for (String key : phases) {
                    descriptionBuffer.append("* ").append(key).append(": ");
                    if (lifecycle.getDefaultPhases().get(key) != null) {
                        descriptionBuffer
                                .append(lifecycle.getDefaultPhases().get(key))
                                .append(LS);
                    } else {
                        descriptionBuffer.append(NOT_DEFINED).append(LS);
                    }
                }
            }
            return false;
        }

        // goals
        MojoDescriptor mojoDescriptor;
        try {
            mojoDescriptor = mojoDescriptorCreator.getMojoDescriptor(cmd, session, project);
        } catch (Exception e) {
            throw new MojoException("Unable to get descriptor for " + cmd, e);
        }
        descriptionBuffer
                .append("'")
                .append(cmd)
                .append("' is a plugin goal (aka mojo)")
                .append(".");
        descriptionBuffer.append(LS);
        plugin = mojoDescriptor.getPluginDescriptor().getId();
        goal = mojoDescriptor.getGoal();

        return true;
    }

    /**
     * Invoke the following private method
     * <code>HelpMojo#toLines(String, int, int, int)</code>
     *
     * @param text       The text to split into lines, must not be <code>null</code>.
     * @param indent     The base indentation level of each line, must not be negative.
     * @param indentSize The size of each indentation, must not be negative.
     * @param lineLength The length of the line, must not be negative.
     * @return The sequence of display lines, never <code>null</code>.
     * @throws MojoException   if any can not invoke the method
     * @throws MojoException if no line was found for <code>text</code>
     */
    private static List<String> toLines(String text, int indent, int indentSize, int lineLength)
            throws MojoException, MojoException {
        try {
            Method m =
                    HelpMojo.class.getDeclaredMethod("toLines", String.class, Integer.TYPE, Integer.TYPE, Integer.TYPE);
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> output = (List<String>) m.invoke(HelpMojo.class, text, indent, indentSize, lineLength);

            if (output == null) {
                throw new MojoException("No output was specified.");
            }

            return output;
        } catch (SecurityException e) {
            throw new MojoException("SecurityException: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new MojoException("IllegalArgumentException: " + e.getMessage());
        } catch (NoSuchMethodException e) {
            throw new MojoException("NoSuchMethodException: " + e.getMessage());
        } catch (IllegalAccessException e) {
            throw new MojoException("IllegalAccessException: " + e.getMessage());
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();

            if (cause instanceof NegativeArraySizeException) {
                throw new MojoException("NegativeArraySizeException: " + cause.getMessage());
            }

            throw new MojoException("InvocationTargetException: " + e.getMessage());
        }
    }

    /**
     * Append a description to the buffer by respecting the indentSize and lineLength parameters.
     * <b>Note</b>: The last character is always a new line.
     *
     * @param sb          The buffer to append the description, not <code>null</code>.
     * @param description The description, not <code>null</code>.
     * @param indent      The base indentation level of each line, must not be negative.
     * @throws MojoException   if any reflection exceptions occur.
     * @throws MojoException if any
     * @see #toLines(String, int, int, int)
     */
    private static void append(StringBuilder sb, String description, int indent)
            throws MojoException, MojoException {
        if (description == null || description.isEmpty()) {
            sb.append(UNKNOWN).append(LS);
            return;
        }

        for (String line : toLines(description, indent, INDENT_SIZE, LINE_LENGTH)) {
            sb.append(line).append(LS);
        }
    }

    /**
     * Append a description to the buffer by respecting the indentSize and lineLength parameters.
     * <b>Note</b>: The last character is always a new line.
     *
     * @param sb     The buffer to append the description, not <code>null</code>.
     * @param key    The key, not <code>null</code>.
     * @param value  The value associated to the key, could be <code>null</code>.
     * @param indent The base indentation level of each line, must not be negative.
     * @throws MojoException   if any reflection exceptions occur.
     * @throws MojoException if any
     * @see #toLines(String, int, int, int)
     */
    private static void append(StringBuilder sb, String key, String value, int indent)
            throws MojoException, MojoException {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("Key is required!");
        }

        if (value == null || value.isEmpty()) {
            value = UNKNOWN;
        }

        String description = key + ": " + value;
        for (String line : toLines(description, indent, INDENT_SIZE, LINE_LENGTH)) {
            sb.append(line).append(LS);
        }
    }

    /**
     * Append a description to the buffer by respecting the indentSize and lineLength parameters for the first line,
     * and append the next lines with <code>indent + 1</code> like a paragraph.
     * <b>Note</b>: The last character is always a new line.
     *
     * @param sb     The buffer to append the description, not <code>null</code>.
     * @param key    The key, not <code>null</code>.
     * @param value  The value, could be <code>null</code>.
     * @param indent The base indentation level of each line, must not be negative.
     * @throws MojoException   if any reflection exceptions occur.
     * @throws MojoException if any
     * @see #toLines(String, int, int, int)
     */
    private static void appendAsParagraph(StringBuilder sb, String key, String value, int indent)
            throws MojoException, MojoException {
        if (value == null || value.isEmpty()) {
            value = UNKNOWN;
        }

        String description;
        if (key == null) {
            description = value;
        } else {
            description = key + ": " + value;
        }

        List<String> l1 = toLines(description, indent, INDENT_SIZE, LINE_LENGTH - INDENT_SIZE);
        List<String> l2 = toLines(description, indent + 1, INDENT_SIZE, LINE_LENGTH);
        l2.set(0, l1.get(0));
        for (String line : l2) {
            sb.append(line).append(LS);
        }
    }

    /**
     * Determines if this Mojo should be used as a report or not. This resolves the plugin project along with all of its
     * transitive dependencies to determine if the Java class of this goal implements <code>MavenReport</code>.
     *
     * @param md Mojo descriptor
     * @return Whether this goal should be used as a report.
     */
    private boolean isReportGoal(MojoDescriptor md) {
        PluginDescriptor pd = md.getPluginDescriptor();
        List<URL> urls = new ArrayList<>();
        ProjectBuildingRequest pbr = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
        pbr.setRemoteRepositories(project.getRemoteArtifactRepositories());
        pbr.setPluginArtifactRepositories(project.getPluginArtifactRepositories());
        pbr.setResolveDependencies(true);
        pbr.setProject(null);
        pbr.setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL);
        try {
            Artifact jar = resolveArtifact(
                            new DefaultArtifact(pd.getGroupId(), pd.getArtifactId(), "jar", pd.getVersion()))
                    .getArtifact();
            Artifact pom = resolveArtifact(
                            new DefaultArtifact(pd.getGroupId(), pd.getArtifactId(), "pom", pd.getVersion()))
                    .getArtifact();
            MavenProject mavenProject = projectBuilder.build(pom.getFile(), pbr).getProject();
            urls.add(jar.getFile().toURI().toURL());
            for (String artifact : mavenProject.getCompileClasspathElements()) {
                urls.add(new File(artifact).toURI().toURL());
            }
            try (URLClassLoader classLoader =
                    new URLClassLoader(urls.toArray(new URL[0]), getClass().getClassLoader())) {
                return MavenReport.class.isAssignableFrom(Class.forName(md.getImplementation(), false, classLoader));
            }
        } catch (Exception e) {
            getLog().warn("Couldn't identify if this goal is a report goal: " + e.getMessage());
            return false;
        }
    }

    private MessageBuilder buffer() {
        return messageBuilderFactory.builder();
    }

    /**
     * Gets the effective string to use for the plugin/mojo/parameter description.
     *
     * @param description The description of the element, may be <code>null</code>.
     * @return The effective description string, never <code>null</code>.
     */
    private static String toDescription(String description) {
        if (description != null && !description.isEmpty()) {
            return new HtmlToPlainTextConverter().convert(description);
        }

        return "(no description available)";
    }

    /**
     * Class to wrap Plugin information.
     */
    static class PluginInfo {
        private String prefix;

        private String groupId;

        private String artifactId;

        private String version;

        /**
         * @return the prefix
         */
        public String getPrefix() {
            return prefix;
        }

        /**
         * @param prefix the prefix to set
         */
        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        /**
         * @return the groupId
         */
        public String getGroupId() {
            return groupId;
        }

        /**
         * @param groupId the groupId to set
         */
        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        /**
         * @return the artifactId
         */
        public String getArtifactId() {
            return artifactId;
        }

        /**
         * @param artifactId the artifactId to set
         */
        public void setArtifactId(String artifactId) {
            this.artifactId = artifactId;
        }

        /**
         * @return the version
         */
        public String getVersion() {
            return version;
        }

        /**
         * @param version the version to set
         */
        public void setVersion(String version) {
            this.version = version;
        }
    }
}
