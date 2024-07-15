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

import javax.xml.XMLConstants;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.codehaus.plexus.util.xml.XMLWriter;
import org.codehaus.plexus.util.xml.XmlWriterUtil;
import org.jdom2.Document;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;

/**
 * Base class with common utilities to write effective Pom/settings.
 *
 * @author <a href="mailto:vincent.siveton@gmail.com">Vincent Siveton</a>
 * @since 2.1
 */
public abstract class AbstractEffectiveMojo extends AbstractHelpMojo {

    /**
     * This options gives the option to output information in cases where the output has been suppressed by using
     * <code>-q</code> (quiet option) in Maven.
     *
     * @since 4.0.0
     */
    @Parameter(property = "forceStdout", defaultValue = "false")
    protected boolean forceStdout;

    @Inject
    protected Session session;

    /**
     * Write comments in the Effective POM/settings header.
     *
     * @param writer not null
     */
    protected static void writeHeader(XMLWriter writer) {
        XmlWriterUtil.writeCommentLineBreak(writer);
        XmlWriterUtil.writeComment(writer, " ");
        XmlWriterUtil.writeComment(writer, "Generated by Maven Help Plugin");
        XmlWriterUtil.writeComment(writer, "See: https://maven.apache.org/plugins/maven-help-plugin/");
        XmlWriterUtil.writeComment(writer, " ");
        XmlWriterUtil.writeCommentLineBreak(writer);
    }

    /**
     * Write comments in a normalize way.
     *
     * @param writer not null
     * @param comment not null
     */
    protected static void writeComment(XMLWriter writer, String comment) {
        XmlWriterUtil.writeCommentLineBreak(writer);
        XmlWriterUtil.writeComment(writer, " ");
        XmlWriterUtil.writeComment(writer, comment);
        XmlWriterUtil.writeComment(writer, " ");
        XmlWriterUtil.writeCommentLineBreak(writer);
    }

    /**
     * @param effectiveModel not null
     * @param encoding not null
     * @param omitDeclaration whether the XML declaration should be omitted from the effective pom
     * @return pretty format of the xml or the original {@code effectiveModel} if an error occurred.
     */
    protected static String prettyFormat(String effectiveModel, String encoding, boolean omitDeclaration) {
        SAXBuilder builder = new SAXBuilder();
        builder.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        builder.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        try {
            Document effectiveDocument = builder.build(new StringReader(effectiveModel));

            StringWriter w = new StringWriter();
            Format format = Format.getPrettyFormat();
            if (encoding != null) {
                // This is a design flaw in JDOM, no NPE on null arguments, but null is not prohibited
                format.setEncoding(encoding);
            }
            format.setLineSeparator(System.lineSeparator());
            format.setOmitDeclaration(omitDeclaration);
            XMLOutputter out = new XMLOutputter(format);
            out.output(effectiveDocument, w);

            return w.toString();
        } catch (JDOMException | IOException e) {
            return effectiveModel;
        }
    }
}
