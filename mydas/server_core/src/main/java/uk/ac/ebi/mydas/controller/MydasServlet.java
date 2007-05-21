/*
 * Copyright 2007 Philip Jones, EMBL-European Bioinformatics Institute
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
 *
 *
 * For further details of the mydas project, including source code,
 * downloads and documentation, please see:
 *
 * http://code.google.com/p/mydas/
 *
 */

package uk.ac.ebi.mydas.controller;

import org.apache.log4j.Logger;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;
import uk.ac.ebi.mydas.datasource.ReferenceDataSource;
import uk.ac.ebi.mydas.datasource.RangeHandlingReferenceDataSource;
import uk.ac.ebi.mydas.datasource.RangeHandlingAnnotationDataSource;
import uk.ac.ebi.mydas.datasource.AnnotationDataSource;
import uk.ac.ebi.mydas.exceptions.*;
import uk.ac.ebi.mydas.model.*;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import java.net.URL;

/**
 * Created Using IntelliJ IDEA.
 * User: phil
 * Date: 04-May-2007
 * Time: 12:10:01
 * A DAS server allowing the easy creation of plugins to different data
 * sources that does not tie in the plugin developer to any particular API
 * (apart from the very simple interfaces defined by this API.)
 *
 * This DAS server provides a complete implementation of
 * <a href="http://biodas.org/documents/spec.html">
 *     Distributed Sequence Annotation Systems (DAS) Version 1.53
 * </a>
 *
 * @author Phil Jones, EMBL EBI, pjones@ebi.ac.uk
 */
public class MydasServlet extends HttpServlet {

    /**
     * Define a static logger variable so that it references the
     * Logger instance named "XMLUnmarshaller".
     */
    private static final Logger logger = Logger.getLogger(MydasServlet.class);

    /**
     * This pattern is used to parse the URI part of the request.
     * Returns two groups:
     *
     * <b>dsn command</b>
     * Group 1: "dsn"
     * Group 2: ""
     *
     * <b>All other commands</b>
     * Group 1: "DSN_NAME"
     * Group 2: "command"
     *
     * The URI part of the request as returned by <code>request.getRequestURI();</code>
     * should look like one of the following examples:
     *
     [PREFIX]/das/dsn

     [PREFIX]/das/dsnname/entry_points
     [PREFIX]/das/dsnname/dna
     [PREFIX]/das/dsnname/sequenceString
     [PREFIX]/das/DSNNAME/types
     [PREFIX]/das/dsnname/features
     [PREFIX]/das/dsnname/link
     [PREFIX]/das/dsnname/stylesheet
     */
    private static final Pattern REQUEST_URI_PATTERN = Pattern.compile ("/das/([^\\s/?]+)/?([^\\s/?]*)$");

    /**
     * Pattern used to parse a segment range, as used for the dna and sequenceString commands.
     * This can be used based on the assumption that the segments have already been split
     * into indidual Strings (i.e. by splitting on the ; character).
     * Three groups are returned from a match as follows:
     * Group 1: segment name
     * Group 3: start coordinate
     * Group 4: stop coordinate
     */
    private static final Pattern SEGMENT_RANGE_PATTERN = Pattern.compile ("^segment=([^:\\s]*)(:(\\d+),(\\d+))?$");

    private static DataSourceManager DATA_SOURCE_MANAGER = null;

    private static final String RESOURCE_FOLDER = "/";
//    private static final String RESOURCE_FOLDER = "/WEB-INF/classes/";

    private static final String CONFIGURATION_FILE_NAME = RESOURCE_FOLDER + "MydasServerConfig.xml";

    /*
     Status codes for the DAS server.
     */
    private static final String STATUS_200_OK = "200";
    private static final String STATUS_400_BAD_COMMAND = "400";
    private static final String STATUS_401_BAD_DATA_SOURCE = "401";
    private static final String STATUS_402_BAD_COMMAND_ARGUMENTS = "402";
    private static final String STATUS_403_BAD_REFERENCE_OBJECT = "403";
    private static final String STATUS_404_BAD_STYLESHEET = "404";
    private static final String STATUS_405_COORDINATE_ERROR = "405";
    private static final String STATUS_500_SERVER_ERROR = "500";
    private static final String STATUS_501_UNIMPLEMENTED_FEATURE = "501";

    /*
     Commands handled by the servlet.
     */
    private static final String COMMAND_DSN = "dsn";
    private static final String COMMAND_DNA = "dna";
    private static final String COMMAND_TYPES = "types";
    private static final String COMMAND_LINK = "link";
    private static final String COMMAND_STYLESHEET = "stylesheet";
    private static final String COMMAND_FEATURES = "features";
    private static final String COMMAND_ENTRY_POINTS = "entry_points";
    private static final String COMMAND_SEQUENCE = "sequence";

    /**
     * List<String> of valid 'field' parameters for the link command.
     */
    public static final List<String> VALID_LINK_COMMAND_FIELDS = new ArrayList<String>(5);
    static {
        VALID_LINK_COMMAND_FIELDS.add(AnnotationDataSource.LINK_FIELD_CATEGORY);
        VALID_LINK_COMMAND_FIELDS.add(AnnotationDataSource.LINK_FIELD_FEATURE);
        VALID_LINK_COMMAND_FIELDS.add(AnnotationDataSource.LINK_FIELD_METHOD);
        VALID_LINK_COMMAND_FIELDS.add(AnnotationDataSource.LINK_FIELD_TARGET);
        VALID_LINK_COMMAND_FIELDS.add(AnnotationDataSource.LINK_FIELD_TYPE);
    }
    /*
        Response Header line keys
     */
    private static final String HEADER_KEY_X_DAS_VERSION = "X-DAS-Version";
    private static final String HEADER_KEY_X_DAS_STATUS = "X-DAS-Status";
    private static final String HEADER_KEY_X_DAS_CAPABILITIES = "X-DAS-Capabilities";

    /*
        Response Header line values
     */
    private static final String HEADER_VALUE_CAPABILITIES = "dsn/1.0; dna/1.0; types/1.0; stylesheet/1.0; features/1.0; entry_points/1.0; error-segment/1.0; unknown-segment/1.0; feature-by-id/1.0; group-by-id/1.0; component/1.0; supercomponent/1.0; sequenceString/1.0";
    private static final String HEADER_VALUE_DAS_VERSION = "DAS/1.5";


    /*
        Content encoding
     */
    private static final String ENCODING_REQUEST_HEADER_KEY = "Accept-Encoding";
    private static final String ENCODING_RESPONSE_HEADER_KEY = "Content-Encoding";
    private static final String ENCODING_GZIPPED = "gzip";

    /*
        Configuration for the output XML
     */
    private static final String DAS_XML_NAMESPACE = null;

    private static XmlPullParserFactory PULL_PARSER_FACTORY = null;
    private static final String INDENTATION_PROPERTY = "http://xmlpull.org/v1/doc/properties.html#serializer-indentation";
    private static final String INDENTATION_PROPERTY_VALUE = "  ";


    /**
     * This method will ensure that all the plugins are registered and call
     * the corresonding init() method on all of the plugins.
     *
     * Also initialises the XMLPullParser factory.
     * @throws ServletException
     */
    public void init() throws ServletException {
        super.init();

        // Initialise data sources.
        if (DATA_SOURCE_MANAGER == null){
            DATA_SOURCE_MANAGER = new DataSourceManager(this.getServletContext());
            try{
                DATA_SOURCE_MANAGER.init(CONFIGURATION_FILE_NAME);
            }
            catch (Exception e){
                // Something fatal has happened.  Need to barf out at this point and warn the person who has deployed the service.
                logger.error ("Fatal Exception thrown at initialisation.  None of the datasources will be usable.", e);
                throw new IllegalStateException ("Fatal Exception thrown at initialisation.  None of the datasources will be usable.", e);
            }
        }

        // Initialize XMLPullParserFactory for marshaller.
        if (PULL_PARSER_FACTORY == null) {
            try {
                PULL_PARSER_FACTORY = XmlPullParserFactory.newInstance(System.getProperty(XmlPullParserFactory.PROPERTY_NAME), null);
                PULL_PARSER_FACTORY.setNamespaceAware(true);
            } catch (XmlPullParserException xppe) {
                logger.error("Fatal Exception thrown at initialisation.  Cannot initialise the PullParserFactory required to allow generation of the DAS XML.", xppe);
                throw new IllegalStateException ("Fatal Exception thrown at initialisation.  Cannot initialise the PullParserFactory required to allow generation of the DAS XML.", xppe);
            }
        }
    }

    /**
     * This method will ensure that call the corresponding destroy() method on
     * all of the registered plugins to allow them to clean up resources.
     */
    public void destroy() {
        super.destroy();

        if (DATA_SOURCE_MANAGER != null){
            DATA_SOURCE_MANAGER.destroy();
        }
    }

    /**
     * Delegates to the parseAndHandleRequest method
     * @param request
     * @param response
     * @throws ServletException
     * @throws IOException
     */
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        parseAndHandleRequest(request, response);
    }

    /**
     * Delegates to the parseAndHandleRequest method
     * @param request
     * @param response
     * @throws ServletException
     * @throws IOException
     */
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        parseAndHandleRequest(request, response);
    }

    /**
     * Handles requests encoded as GET or POST.
     * First of all splits up the request and then delegates to appropriate method
     * to feed this request.
     * @param request The http request object.
     * @param response The response - normally an XML file in HTTP/1.0 protocol.
     * @throws ServletException in the event of an internal error
     * @throws IOException in the event of a low level I/O error.
     */
    private void parseAndHandleRequest(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        // Parse the request URI (e.g. /das/dsnname/sequenceString).
        String queryString = request.getQueryString();

        if (logger.isDebugEnabled()){
            logger.debug("RequestURI: '" + request.getRequestURI() + "'");
            logger.debug("Query String: '" + queryString + "'");
        }

        Matcher match = REQUEST_URI_PATTERN.matcher(request.getRequestURI());

        try{
            // Belt and braces to ensure that no null pointers are thrown later.
            if (DATA_SOURCE_MANAGER == null ||
                    DATA_SOURCE_MANAGER.getServerConfiguration() == null ||
                    DATA_SOURCE_MANAGER.getServerConfiguration().getGlobalConfiguration() == null ||
                    DATA_SOURCE_MANAGER.getServerConfiguration().getDataSourceConfigMap() == null){

                throw new ConfigurationException("The datasources were not initialized successfully.");
            }

            if (match.find()){
                // Check first for the dsn command (has a different format to all the others, so start here).
                if (COMMAND_DSN.equals(match.group(1))){
                    // Handle dsn command, after checking there is no guff in the URI after it.
                    if (match.group(2) == null || match.group(2).length() == 0){
                        // All good, send command.
                        dsnCommand (request, response, queryString);
                    }
                    else {
                        // Starts off looking like the dsn command, but has some other stuff after it...
                        throw new BadCommandException("A bad dsn command has been sent to the server, including unrecognised additional query parameters.");
                    }
                }

                // Not the dsn command, so handle other commands (which are datasource specific)
                else {
                    String dsnName = match.group(1);
                    String command = match.group(2);
                    // Attempt to retrieve the DataSource
                    DataSourceConfiguration dataSourceConfig = DATA_SOURCE_MANAGER.getServerConfiguration().getDataSourceConfigMap().get(dsnName);
                    // Check if the datasource exists.
                    if (dataSourceConfig != null){
                        // Check the datasource is alive.
                        if (dataSourceConfig.isOK()){
                            if      (COMMAND_DNA.equals(command)){
                                dnaCommand (request, response, dataSourceConfig, queryString);
                            }
                            else if (COMMAND_TYPES.equals(command)){
                                typesCommand (request, response, dataSourceConfig, queryString);
                            }
                            else if (COMMAND_STYLESHEET.equals(command)){
                                stylesheetCommand (request, response, dataSourceConfig, queryString);
                            }
                            else if (COMMAND_FEATURES.equals(command)){
                                featuresCommand (request, response, dataSourceConfig, queryString);
                            }
                            else if (COMMAND_ENTRY_POINTS.equals(command)){
                                entryPointsCommand (request, response, dataSourceConfig, queryString);
                            }
                            else if (COMMAND_SEQUENCE.equals(command)){
                                sequenceCommand (request, response, dataSourceConfig, queryString);
                            }
                            else if (COMMAND_LINK.equals(command)){
                                linkCommand (response, dataSourceConfig, queryString);
                            }
                            else {
                                throw new BadCommandException("The command is not recognised.");
                            }
                        }
                        else{
                            throw new BadDataSourceException("The datasource was not correctly initialised.");
                        }
                    }
                    else {
                        throw new BadDataSourceException("The requested datasource does not exist.");
                    }
                }
            }
            else {
                throw new BadCommandException("The command is not recognised.");
            }
        }
        catch (XmlPullParserException xppe) {
            logger.error("XmlPullParserException thrown when attempting to ouput XML.", xppe);
            writeHeader (request, response, STATUS_500_SERVER_ERROR, false);
        } catch (DataSourceException dse){
            logger.error("DataSourceException thrown by a data source.", dse);
            writeHeader(request, response, STATUS_500_SERVER_ERROR, false);
        } catch (BadCommandArgumentsException bcae) {
            logger.error("BadCommandArgumentsException thrown", bcae);
            writeHeader(request, response, STATUS_402_BAD_COMMAND_ARGUMENTS, false);
        } catch (BadReferenceObjectException broe) {
            logger.error("BadReferenceObjectException thrown", broe);
            writeHeader(request, response, STATUS_403_BAD_REFERENCE_OBJECT, false);
        } catch (CoordinateErrorException cee) {
            logger.error("CoordinateErrorException thrown", cee);
            writeHeader(request, response, STATUS_405_COORDINATE_ERROR, false);
        } catch (UnimplementedFeatureException efe) {
            logger.error("UnimplementedFeatureException thrown", efe);
            writeHeader(request, response, STATUS_501_UNIMPLEMENTED_FEATURE, false);
        } catch (BadCommandException bce) {
            logger.error("BadCommandException thrown", bce);
            writeHeader(request, response, STATUS_400_BAD_COMMAND, false);
        } catch (BadDataSourceException bdse) {
            logger.error("BadDataSourceException thrown", bdse);
            writeHeader(request, response, STATUS_401_BAD_DATA_SOURCE, false);
        } catch (ConfigurationException ce) {
            logger.error("ConfigurationException thrown: This mydas installation was not correctly initialised.", ce);
            writeHeader(request, response, STATUS_500_SERVER_ERROR, false);
        }
    }

    /**
     * Implements the dsn command.  Only reports dsns that have initialised successfully.
     * @param request
     * @param response
     * @param queryString
     * @throws XmlPullParserException
     * @throws IOException
     */
    private void dsnCommand(HttpServletRequest request, HttpServletResponse response, String queryString)
            throws XmlPullParserException, IOException{
        // Check the configuration has been loaded successfully
        if (DATA_SOURCE_MANAGER.getServerConfiguration() == null){
            writeHeader (request, response, STATUS_500_SERVER_ERROR, false);
            logger.error("A request has been made to the das server, however initialisation failed - possibly the mydasserverconfig.xml file was not found.");
            return;
        }
        // Check there is nothing in the query string.
        if (queryString == null || queryString.length() == 0){
            // All fine.
            // Get the list of dsn from the DataSourceManager
            List<String> dsns = DATA_SOURCE_MANAGER.getServerConfiguration().getDsnNames();
            // Check there is at least one dsn.  (Mandatory in the dsn XML output).
            if (dsns == null || dsns.size() == 0){
                writeHeader (request, response, STATUS_500_SERVER_ERROR, false);
                logger.error("The dsn command has been called, but no dsns have been initialised successfully.");
            }
            else{
                // At least one dsn is OK.
                writeHeader (request, response, STATUS_200_OK, true);
                // Build the XML.
                XmlSerializer serializer;
                serializer = PULL_PARSER_FACTORY.newSerializer();
                BufferedWriter out = null;
                try{
                    out = getResponseWriter(request, response);
                    serializer.setOutput(out);
                    serializer.setProperty(INDENTATION_PROPERTY, INDENTATION_PROPERTY_VALUE);
                    serializer.startDocument(null, false);
                    serializer.text("\n");
                    serializer.docdecl(" DASDSN SYSTEM \"http://www.biodas.org/dtd/dasdsn.dtd\"");
                    serializer.text("\n");
                    serializer.startTag (DAS_XML_NAMESPACE, "DASDSN");
                    for (String dsn : dsns){
                        DataSourceConfiguration dsnConfig = DATA_SOURCE_MANAGER.getServerConfiguration().getDataSourceConfig(dsn);
                        serializer.startTag (DAS_XML_NAMESPACE, "DSN");
                        serializer.startTag (DAS_XML_NAMESPACE, "SOURCE");
                        serializer.attribute(DAS_XML_NAMESPACE, "id", dsnConfig.getId());

                        // Optional version attribute.
                        if (dsnConfig.getVersion() != null && dsnConfig.getVersion().length() > 0){
                            serializer.attribute(DAS_XML_NAMESPACE, "version", dsnConfig.getVersion());
                        }

                        // If a name has been set, this is used for the element text.  Otherwise, the id is used.
                        if (dsnConfig.getName() != null && dsnConfig.getName().length() > 0){
                            serializer.text(dsnConfig.getName());
                        }
                        else {
                            serializer.text(dsnConfig.getId());
                        }
                        serializer.endTag (DAS_XML_NAMESPACE, "SOURCE");
                        serializer.startTag (DAS_XML_NAMESPACE, "MAPMASTER");
                        serializer.text(dsnConfig.getMapmaster());
                        serializer.endTag (DAS_XML_NAMESPACE, "MAPMASTER");

                        // Optional description element.
                        if (dsnConfig.getDescription() != null && dsnConfig.getDescription().length() > 0){
                            serializer.startTag(DAS_XML_NAMESPACE, "DESCRIPTION");
                            serializer.text(dsnConfig.getDescription());
                            serializer.endTag(DAS_XML_NAMESPACE, "DESCRIPTION");
                        }
                        serializer.endTag (DAS_XML_NAMESPACE, "DSN");
                    }
                    serializer.endTag (DAS_XML_NAMESPACE, "DASDSN");
                    serializer.flush();
                }
                finally{
                    if (out != null){
                        out.close();
                    }
                }
            }
        }
        else {
            // If fallen through to here, then the dsn command is not recognised
            // as it has rubbish in the query string.
            writeHeader (request, response, STATUS_402_BAD_COMMAND_ARGUMENTS, true);
        }
    }

    private void dnaCommand(HttpServletRequest request, HttpServletResponse response, DataSourceConfiguration dsnConfig, String queryString)
            throws XmlPullParserException, IOException, DataSourceException, UnimplementedFeatureException,
            BadReferenceObjectException, BadCommandArgumentsException, CoordinateErrorException {
        // Is the dna command enabled?
        if (dsnConfig.isDnaCommandEnabled()){
            // Is this a reference source?
            if (dsnConfig.getDataSource() instanceof ReferenceDataSource){
                // All good - process command.
                Collection<SequenceReporter> sequences = getSequences(dsnConfig, queryString);
                // Got some sequences, so all is OK.
                writeHeader (request, response, STATUS_200_OK, true);
                // Build the XML.
                XmlSerializer serializer;
                serializer = PULL_PARSER_FACTORY.newSerializer();
                BufferedWriter out = null;
                try{
                    out = getResponseWriter(request, response);
                    serializer.setOutput(out);
                    serializer.setProperty(INDENTATION_PROPERTY, INDENTATION_PROPERTY_VALUE);
                    serializer.startDocument(null, false);
                    serializer.text("\n");
                    serializer.docdecl(" DASDNA SYSTEM \"http://www.biodas.org/dtd/dasdna.dtd\"");
                    serializer.text("\n");
                    // Now the body of the DASDNA xml.
                    serializer.startTag (DAS_XML_NAMESPACE, "DASDNA");
                    for (SequenceReporter sequenceReporter : sequences){
                        serializer.startTag(DAS_XML_NAMESPACE, "SEQUENCE");
                        serializer.attribute(DAS_XML_NAMESPACE, "id", sequenceReporter.getSegmentName());
                        serializer.attribute(DAS_XML_NAMESPACE, "start", Integer.toString(sequenceReporter.getStart()));
                        serializer.attribute(DAS_XML_NAMESPACE, "stop", Integer.toString(sequenceReporter.getStop()));
                        serializer.attribute(DAS_XML_NAMESPACE, "version", sequenceReporter.getSequenceVersion());
                        serializer.startTag(DAS_XML_NAMESPACE, "DNA");
                        serializer.attribute(DAS_XML_NAMESPACE, "length", Integer.toString(sequenceReporter.getSequenceString().length()));
                        serializer.text(sequenceReporter.getSequenceString());
                        serializer.endTag(DAS_XML_NAMESPACE, "DNA");
                        serializer.endTag(DAS_XML_NAMESPACE, "SEQUENCE");
                    }
                    serializer.endTag (DAS_XML_NAMESPACE, "DASDNA");
                }
                finally{
                    if (out != null){
                        out.close();
                    }
                }
            }
            else {
                // Not a reference source.
                throw new UnimplementedFeatureException("The dna command has been called on an annotation server.");
            }
        }
        else{
            // dna command disabled
            throw new UnimplementedFeatureException("The dna command has been disabled for this data source.");
        }
    }

    private void typesCommand(HttpServletRequest request, HttpServletResponse response, DataSourceConfiguration dsnConfig, String queryString)
            throws BadCommandArgumentsException, BadReferenceObjectException, DataSourceException, CoordinateErrorException, IOException, XmlPullParserException {
        // Parse the queryString to retrieve the individual parts of the query.

        List<SegmentQuery> requestedSegments = new ArrayList<SegmentQuery>();
        List<String> typeFilter = new ArrayList<String>();
        /************************************************************************\
         * Parse the query string                                               *
         ************************************************************************/
        // It is legal for the query string to be empty for the types command.
        if (queryString != null && queryString.length() > 0){
            // Split on the ; (delineates the separate parts of the query)
            String[] queryParts = queryString.split(";");
            for (String queryPart : queryParts){
                boolean queryPartParsable = false;
                // Now determine what each part is, and construct the query.
                Matcher segmentRangeMatcher = SEGMENT_RANGE_PATTERN.matcher(queryPart);
                if (segmentRangeMatcher.find()){
                    requestedSegments.add (new SegmentQuery (segmentRangeMatcher));
                    queryPartParsable = true;
                }
                else{
                    // Split the queryPart on "=" and see if the result is parsable.
                    String[] queryPartKeysValues = queryPart.split("=");
                    if (queryPartKeysValues.length != 2){
                        // All of the remaining query parts are key=value pairs, so this is a bad argument.
                        throw new BadCommandArgumentsException("Bad command arguments to the features command: " + queryString);
                    }
                    String key = queryPartKeysValues[0];
                    String value = queryPartKeysValues[1];
                    // Check for typeId restriction
                    if ("type".equals (key)){
                        typeFilter.add(value);
                        queryPartParsable = true;
                    }
                }
                // If not parsable, throw a BadCommandArgumentsException
                if (! queryPartParsable){
                    throw new BadCommandArgumentsException("Bad command arguments to the features command: " + queryString);
                }
            }
        }
        if (requestedSegments.size() == 0){
            // Process the types command for all types - not segment specific.
            typesCommandAllTypes(request, response, dsnConfig, typeFilter);
        }
        else {
            // Process the types command for specific segments.
            typesCommandSpecificSegments(request, response, dsnConfig, requestedSegments, typeFilter);
        }
    }



    private void typesCommandAllTypes (HttpServletRequest request, HttpServletResponse response,
                                       DataSourceConfiguration dsnConfig, List<String> typeFilter)
            throws DataSourceException, XmlPullParserException, IOException {
        // Handle no segments indicated - just give a single 'dummy' segment that describes the types for the
        // whole dsn.

        // Build a Map of Types to DasType counts. (the counts being Integer objects set to 'null' until
        // a count is retrieved.
        Map<DasType, Integer> allTypesReport = null;
        Collection<DasType> allTypes = dsnConfig.getDataSource().getTypes();
        if (allTypes != null){
            allTypesReport = new HashMap<DasType, Integer>(allTypes.size());
            for (DasType type : allTypes){
                if (type != null){
                    // Check if the type_ids have been filtered in the request.
                    if (typeFilter.size() == 0 || typeFilter.contains(type.getId())){
                        // Attempt to get a count of the types from the dsn. (May not be implemented.)
                        Integer typeCount;
                        try{
                            typeCount = dsnConfig.getDataSource().getTotalCountForType (type.getId());
                        } catch (UnimplementedFeatureException e) {
                            typeCount = null;
                        }
                        allTypesReport.put (type, typeCount);
                    }
                }
            }
        }
        else{
            allTypesReport = Collections.EMPTY_MAP;
        }

        writeHeader (request, response, STATUS_200_OK, true);
        // Build the XML.
        XmlSerializer serializer;
        serializer = PULL_PARSER_FACTORY.newSerializer();
        BufferedWriter out = null;
        try{
            out = getResponseWriter(request, response);
            serializer.setOutput(out);
            serializer.setProperty(INDENTATION_PROPERTY, INDENTATION_PROPERTY_VALUE);
            serializer.startDocument(null, false);
            serializer.text("\n");
            serializer.docdecl(" DASTYPES SYSTEM \"http://www.biodas.org/dtd/dastypes.dtd\"");
            serializer.text("\n");
            // Now the body of the DASTYPES xml.
            serializer.startTag (DAS_XML_NAMESPACE, "DASTYPES");
            serializer.startTag (DAS_XML_NAMESPACE, "GFF");
            serializer.attribute(DAS_XML_NAMESPACE, "version", "1.0");
            serializer.attribute(DAS_XML_NAMESPACE, "href", this.buildRequestHref(request));
            serializer.startTag(DAS_XML_NAMESPACE, "SEGMENT");
            // No id, start, stop, type attributes.
            serializer.attribute(DAS_XML_NAMESPACE, "version", dsnConfig.getVersion());
            serializer.attribute(DAS_XML_NAMESPACE, "label", "Complete datasource summary");
            // Iterate over the allTypeReport for the TYPE elements.
            for (DasType type : allTypesReport.keySet()){
                serializer.startTag(DAS_XML_NAMESPACE, "TYPE");
                serializer.attribute(DAS_XML_NAMESPACE, "id", type.getId());
                if (type.getMethod() != null && type.getMethod().length() > 0){
                    serializer.attribute(DAS_XML_NAMESPACE, "method", type.getMethod());
                }
                if (type.getCategory() != null && type.getCategory().length() > 0){
                    serializer.attribute(DAS_XML_NAMESPACE, "category", type.getCategory());
                }
                if (allTypesReport.get(type) != null){
                    serializer.text(Integer.toString(allTypesReport.get(type)));
                }
                serializer.endTag(DAS_XML_NAMESPACE, "TYPE");
            }
            serializer.endTag(DAS_XML_NAMESPACE, "SEGMENT");
            serializer.endTag (DAS_XML_NAMESPACE, "GFF");
            serializer.endTag (DAS_XML_NAMESPACE, "DASTYPES");
        }
        finally{
            if (out != null){
                out.close();
            }
        }
    }

    private void typesCommandSpecificSegments(HttpServletRequest request, HttpServletResponse response, DataSourceConfiguration dsnConfig, List<SegmentQuery> requestedSegments, List<String> typeFilter)
            throws DataSourceException, BadReferenceObjectException, XmlPullParserException, IOException {
        Map <FoundFeaturesReporter, Map<DasType, Integer>> typesReport =
                new HashMap<FoundFeaturesReporter, Map<DasType, Integer>>(requestedSegments.size());
        // For each segment, populate the typesReport with 'all types' if necessary and then add types and counts.
        for (SegmentQuery segmentQuery : requestedSegments){
            // Try to get the features for this segment
            DasAnnotatedSegment segment = dsnConfig.getDataSource().getFeatures(segmentQuery.getSegmentId());
            if (segment != null){
                FoundFeaturesReporter segmentReporter = new FoundFeaturesReporter(segment, segmentQuery);
                Map<DasType, Integer> segmentTypes = new HashMap<DasType, Integer>();
                // Add these objects to the typesReport.
                typesReport.put(segmentReporter, segmentTypes);
                /////////////////////////////////////////////////////////////////////////////////////////////
                // If required in configuration, add all the types from the server to the segmentTypes map
                if (dsnConfig.isIncludeTypesWithZeroCount()){
                    Collection<DasType> allTypes = dsnConfig.getDataSource().getTypes();
                    // Iterate over allTypes and add each type to the segment types report with a count of zero.
                    for (DasType type : allTypes){
                        // (Filtering as requested for type ids)
                        if (type != null && (typeFilter.size() == 0 || typeFilter.contains(type.getId())))
                            segmentTypes.put(type, 0);
                    }
                }
                // Handled 'include types with zero count'.
                /////////////////////////////////////////////////////////////////////////////////////////////

                /////////////////////////////////////////////////////////////////////////////////////////////
                // Now iterate over the features of the segment and update the types report.
                for (DasFeature feature : segmentReporter.getFeatures(dsnConfig.isFeaturesStrictlyEnclosed())){
                    // (Filtering as requested for type ids)
                    if (typeFilter.size() == 0 || typeFilter.contains(feature.getTypeId())){
                        DasType featureType = new DasType(feature.getTypeId(), feature.getTypeCategory(), feature.getMethodId());
                        if (segmentTypes.keySet().contains(featureType)){
                            segmentTypes.put(featureType, segmentTypes.get(featureType) + 1);
                        }
                        else {
                            segmentTypes.put(featureType, 1);
                        }
                    }
                }
                // Finished with actual features
                /////////////////////////////////////////////////////////////////////////////////////////////
            }
        }

        // OK, successfully built a Map of the types for all the requested segments, so iterate over this and report.
        writeHeader (request, response, STATUS_200_OK, true);
        // Build the XML.
        XmlSerializer serializer;
        serializer = PULL_PARSER_FACTORY.newSerializer();
        BufferedWriter out = null;
        try{
            out = getResponseWriter(request, response);
            serializer.setOutput(out);
            serializer.setProperty(INDENTATION_PROPERTY, INDENTATION_PROPERTY_VALUE);
            serializer.startDocument(null, false);
            serializer.text("\n");
            serializer.docdecl(" DASTYPES SYSTEM \"http://www.biodas.org/dtd/dastypes.dtd\"");
            serializer.text("\n");
            // Now the body of the DASTYPES xml.
            serializer.startTag (DAS_XML_NAMESPACE, "DASTYPES");
            serializer.startTag (DAS_XML_NAMESPACE, "GFF");
            serializer.attribute(DAS_XML_NAMESPACE, "version", "1.0");
            serializer.attribute(DAS_XML_NAMESPACE, "href", this.buildRequestHref(request));
            for (FoundFeaturesReporter featureReporter : typesReport.keySet()){
                serializer.startTag(DAS_XML_NAMESPACE, "SEGMENT");
                serializer.attribute(DAS_XML_NAMESPACE, "id", featureReporter.getSegmentId());
                serializer.attribute(DAS_XML_NAMESPACE, "start", Integer.toString(featureReporter.getStart()));
                serializer.attribute(DAS_XML_NAMESPACE, "stop", Integer.toString(featureReporter.getStop()));
                if (featureReporter.getType() != null && featureReporter.getType().length() > 0){
                    serializer.attribute(DAS_XML_NAMESPACE, "type", featureReporter.getType());
                }
                serializer.attribute(DAS_XML_NAMESPACE, "version", featureReporter.getVersion());
                if (featureReporter.getSegmentLabel() != null && featureReporter.getSegmentLabel().length() > 0){
                    serializer.attribute(DAS_XML_NAMESPACE, "label", featureReporter.getSegmentLabel());
                }
                // Now for the types.
                Map<DasType, Integer> typeMap = typesReport.get(featureReporter);
                for (DasType type : typeMap.keySet()){
                    Integer count = typeMap.get(type);

                    serializer.startTag(DAS_XML_NAMESPACE, "TYPE");
                    serializer.attribute(DAS_XML_NAMESPACE, "id", type.getId());
                    if (type.getMethod() != null && type.getMethod().length() > 0){
                        serializer.attribute(DAS_XML_NAMESPACE, "method", type.getMethod());
                    }
                    if (type.getCategory() != null && type.getCategory().length() > 0){
                        serializer.attribute(DAS_XML_NAMESPACE, "category", type.getCategory());
                    }
                    if (count != null){
                        serializer.text(Integer.toString(count));
                    }
                    serializer.endTag(DAS_XML_NAMESPACE, "TYPE");
                }
                serializer.endTag(DAS_XML_NAMESPACE, "SEGMENT");
            }
            serializer.endTag (DAS_XML_NAMESPACE, "GFF");
            serializer.endTag (DAS_XML_NAMESPACE, "DASTYPES");
        }
        finally{
            if (out != null){
                out.close();
            }
        }
    }

    private void stylesheetCommand(HttpServletRequest request, HttpServletResponse response, DataSourceConfiguration dsnConfig, String queryString)
            throws BadCommandArgumentsException, UnimplementedFeatureException, IOException, DataSourceException {
        // Check the queryString is empty (as it should be).
        if (queryString != null && queryString.trim().length() > 0){
            throw new BadCommandArgumentsException("Arguments have been passed to the stylesheet command, which does not expect any.");
        }
        // Get the name of the stylesheet.
        String stylesheetFileName;
        if (dsnConfig.getStyleSheet() != null && dsnConfig.getStyleSheet().trim().length() > 0){
            stylesheetFileName = dsnConfig.getStyleSheet().trim();
        }
        // These next lines look like potential null-pointer hell - but note that this has been checked robustly in the
        // calling method, so all OK.
        else if (DATA_SOURCE_MANAGER.getServerConfiguration().getGlobalConfiguration().getDefaultStyleSheet() != null
                && DATA_SOURCE_MANAGER.getServerConfiguration().getGlobalConfiguration().getDefaultStyleSheet().trim().length() > 0){
            stylesheetFileName = DATA_SOURCE_MANAGER.getServerConfiguration().getGlobalConfiguration().getDefaultStyleSheet().trim();
        }
        else {
            throw new UnimplementedFeatureException("This data source has not defined a stylesheet.");
        }

        // Need to create a FileReader to read in the stylesheet, wrapped by a PrintStream to stream it out to the browser.
        BufferedReader reader = null;
        BufferedWriter writer = null;
        try{
            reader = new BufferedReader(
                    new InputStreamReader (
                            getServletContext().getResourceAsStream(RESOURCE_FOLDER + stylesheetFileName)
                    )
            );

            if (reader.ready()){
                //OK, managed to open an input reader from the stylesheet, so output the success header.
                writeHeader (request, response, STATUS_200_OK, true);
                writer = getResponseWriter(request, response);
                while (reader.ready()){
                    writer.write(reader.readLine());
                }
            }
            else {
                throw new DataSourceException("A problem has occurred reading in the stylesheet from the open stream");
            }
        }
        finally{
            if (reader != null){
                reader.close();
            }
            if (writer != null){
                writer.close();
            }
        }
    }

    private void linkCommand(HttpServletResponse response, DataSourceConfiguration dataSourceConfig, String queryString)
            throws IOException, BadCommandArgumentsException, DataSourceException, UnimplementedFeatureException {
        // Parse the request
        if (queryString == null || queryString.length() == 0){
            throw new BadCommandArgumentsException("The link command has been called with no arguments.");
        }
        String[] queryParts = queryString.split(";");
        if (queryParts.length != 2){
            throw new BadCommandArgumentsException("The wrong number of arguments have been passed to the link command.");
        }
        String field = null;
        String id = null;
        for (String keyValuePair : queryParts){
            // Split the key=value pairs
            String[] queryPartKeysValues = keyValuePair.split("=");
            if (queryPartKeysValues.length != 2){
                throw new BadCommandArgumentsException("keys and values cannot be extracted from the arguments to the link command");
            }
            if ("field".equals(queryPartKeysValues[0])){
                field = queryPartKeysValues[1];
            }
            else if ("id".equals(queryPartKeysValues[0])){
                id = queryPartKeysValues[1];
            }
            else {
                throw new BadCommandArgumentsException("unknown key to one of the command arguments to the link command");
            }
        }
        if (field == null || ! VALID_LINK_COMMAND_FIELDS.contains(field) || id == null){
            throw new BadCommandArgumentsException("The link command must be passed a valid field and id argument.");
        }

        URL url = dataSourceConfig.getDataSource().getLinkURL(field, id);
        response.sendRedirect(response.encodeRedirectURL(url.toString()));
    }

    private void featuresCommand(HttpServletRequest request, HttpServletResponse response, DataSourceConfiguration dsnConfig, String queryString)
            throws XmlPullParserException, IOException, DataSourceException, BadCommandArgumentsException,
            UnimplementedFeatureException {
        // Parse the queryString to retrieve the individual parts of the query.
        if (queryString == null || queryString.length() == 0){
            throw new BadCommandArgumentsException("Expecting at least one reference in the query string, but found nothing.");
        }

        List<SegmentQuery> requestedSegments = new ArrayList<SegmentQuery>();
        /************************************************************************\
         * Parse the query string                                               *
         ************************************************************************/

        // Split on the ; (delineates the separate parts of the query)
        String[] queryParts = queryString.split(";");
        DasFeatureRequestFilter filter = new DasFeatureRequestFilter ();
        boolean categorize = true;
        for (String queryPart : queryParts){
            boolean queryPartParsable = false;
            // Now determine what each part is, and construct the query.
            Matcher segmentRangeMatcher = SEGMENT_RANGE_PATTERN.matcher(queryPart);
            if (segmentRangeMatcher.find()){
                requestedSegments.add (new SegmentQuery (segmentRangeMatcher));
                queryPartParsable = true;
            }
            else{
                // Split the queryPart on "=" and see if the result is parsable.
                String[] queryPartKeysValues = queryPart.split("=");
                if (queryPartKeysValues.length != 2){
                    // All of the remaining query parts are key=value pairs, so this is a bad argument.
                    throw new BadCommandArgumentsException("Bad command arguments to the features command: " + queryString);
                }
                String key = queryPartKeysValues[0];
                String value = queryPartKeysValues[1];
                // Check for typeId restriction
                if ("type".equals (key)){
                    filter.addTypeId(value);
                    queryPartParsable = true;
                }
                // else check for categoryId restriction
                else if ("category".equals (key)){
                    filter.addCategoryId(value);
                    queryPartParsable = true;
                }
                // else check for categorize restriction
                else if ("categorize".equals (key)){
                    if ("no".equals(value)){
                        categorize = false;
                    }
                    queryPartParsable = true;
                }
                // else check for featureId restriction
                else if ("feature_id".equals (key)){
                    filter.addFeatureId(value);
                    queryPartParsable = true;
                }
                // else check for groupId restriction
                else if ("group_id".equals (key)){
                    filter.addGroupId(value);
                    queryPartParsable = true;
                }
            }
            // If not parsable, throw a BadCommandArgumentsException
            if (! queryPartParsable){
                throw new BadCommandArgumentsException("Bad command arguments to the features command: " + queryString);
            }
        }

        /************************************************************************\
         * Query the DataSource                                                 *
         ************************************************************************/

        // if segments have been included in the request, use the getFeatureCollection method to retrieve them
        // from the data source.  (getFeatureCollection method shared with the 'types' command.)
        Collection<FeaturesReporter> featuresReporterCollection;
        if (requestedSegments.size() > 0){
            featuresReporterCollection = getFeatureCollection(dsnConfig, requestedSegments);
        }
        else {
            // No segments have been requested, so instead check for either feature_id or group_id filters.
            // (If neither of these are present, then throw a BadCommandArgumentsException)
            if (filter.containsFeatureIds() || filter.containsGroupIds()){
                Collection<DasAnnotatedSegment> annotatedSegments =
                        dsnConfig.getDataSource().getFeatures(filter.getFeatureIds(), filter.getGroupIds());
                if (annotatedSegments != null){
                    featuresReporterCollection = new ArrayList<FeaturesReporter>(annotatedSegments.size());
                    for (DasAnnotatedSegment segment : annotatedSegments){
                        featuresReporterCollection.add (new FoundFeaturesReporter(segment));
                    }
                }
                else {
                    // Nothing returned from the datasource.
                    featuresReporterCollection = Collections.EMPTY_LIST;
                }
            }
            else {
                throw new BadCommandArgumentsException("Bad command arguments to the features command: " + queryString);
            }
        }
        // OK - got a Collection of FoundFeaturesReporter objects, so get on with marshalling them out.
        writeHeader (request, response, STATUS_200_OK, true);

        /************************************************************************\
         * Build the XML                                                        *
         ************************************************************************/

        XmlSerializer serializer;
        serializer = PULL_PARSER_FACTORY.newSerializer();
        BufferedWriter out = null;
        try{
            boolean referenceSource = dsnConfig.getDataSource() instanceof ReferenceDataSource;
            out = getResponseWriter(request, response);
            serializer.setOutput(out);
            serializer.setProperty(INDENTATION_PROPERTY, INDENTATION_PROPERTY_VALUE);
            serializer.startDocument(null, false);
            serializer.text("\n");
            serializer.docdecl(" DASGFF SYSTEM \"http://www.biodas.org/dtd/dasgff.dtd\"");
            serializer.text("\n");

            // Rest of the XML.
            serializer.startTag(DAS_XML_NAMESPACE, "DASGFF");
            serializer.startTag(DAS_XML_NAMESPACE, "GFF");
            serializer.attribute(DAS_XML_NAMESPACE, "version", "1.0");
            serializer.attribute(DAS_XML_NAMESPACE, "href", buildRequestHref(request));
            for (FeaturesReporter featuresReporter : featuresReporterCollection){
                if (featuresReporter instanceof UnknownSegmentReporter){
                    serializer.startTag(DAS_XML_NAMESPACE, (referenceSource) ? "ERRORSEGMENT" : "UNKNOWNSEGMENT");
                    serializer.attribute(DAS_XML_NAMESPACE, "id", featuresReporter.getSegmentId());
                    serializer.attribute(DAS_XML_NAMESPACE, "start", Integer.toString(featuresReporter.getStart()));
                    serializer.attribute(DAS_XML_NAMESPACE, "stop", Integer.toString(featuresReporter.getStop()));
                    serializer.endTag(DAS_XML_NAMESPACE, (referenceSource) ? "ERRORSEGMENT" : "UNKNOWNSEGMENT");
                }
                else {
                    FoundFeaturesReporter foundFeaturesReporter = (FoundFeaturesReporter) featuresReporter;
                    serializer.startTag(DAS_XML_NAMESPACE, "SEGMENT");
                    serializer.attribute(DAS_XML_NAMESPACE, "id", foundFeaturesReporter.getSegmentId());
                    serializer.attribute(DAS_XML_NAMESPACE, "start", Integer.toString(foundFeaturesReporter.getStart()));
                    serializer.attribute(DAS_XML_NAMESPACE, "stop", Integer.toString(foundFeaturesReporter.getStop()));
                    if (foundFeaturesReporter.getType() != null && foundFeaturesReporter.getType().length() > 0){
                        serializer.attribute(DAS_XML_NAMESPACE, "type", foundFeaturesReporter.getType());
                    }
                    serializer.attribute(DAS_XML_NAMESPACE, "version", foundFeaturesReporter.getVersion());
                    if (foundFeaturesReporter.getSegmentLabel() != null && foundFeaturesReporter.getSegmentLabel().length() > 0){
                        serializer.attribute(DAS_XML_NAMESPACE, "label", foundFeaturesReporter.getSegmentLabel());
                    }
                    for (DasFeature feature : foundFeaturesReporter.getFeatures(dsnConfig.isFeaturesStrictlyEnclosed())){
                        // Check the feature passes the filter.
                        if (filter.featurePasses(feature)){
                            serializer.startTag(DAS_XML_NAMESPACE, "FEATURE");
                            serializer.attribute(DAS_XML_NAMESPACE, "id", feature.getFeatureId());
                            if (feature.getFeatureLabel() != null && feature.getFeatureLabel().length() > 0){
                                serializer.attribute(DAS_XML_NAMESPACE, "label", feature.getFeatureLabel());
                            }
                            else if (dsnConfig.isUseFeatureIdForFeatureLabel()){
                                serializer.attribute(DAS_XML_NAMESPACE, "label", feature.getFeatureId());
                            }

                            // TYPE element
                            serializer.startTag(DAS_XML_NAMESPACE, "TYPE");
                            serializer.attribute(DAS_XML_NAMESPACE, "id", feature.getTypeId());

                            // Handle DasReferenceFeatures.
                            if (feature instanceof DasComponentFeature){
                                DasComponentFeature refFeature = (DasComponentFeature) feature;
                                serializer.attribute(DAS_XML_NAMESPACE, "reference", "yes");
                                serializer.attribute(DAS_XML_NAMESPACE, "superparts", (refFeature.hasSuperParts()) ? "yes" : "no");
                                serializer.attribute(DAS_XML_NAMESPACE, "subparts", (refFeature.hasSubParts()) ? "yes" : "no");
                            }
                            if (categorize){
                                if (feature.getTypeCategory() != null && feature.getTypeCategory().length() > 0){
                                    serializer.attribute(DAS_XML_NAMESPACE, "category", feature.getTypeCategory());
                                }
                                else {
                                    // To prevent the DAS server from dying, if no category has been set, but
                                    // a category is required, spit out the type ID again as the category.
                                    serializer.attribute(DAS_XML_NAMESPACE, "category", feature.getTypeId());
                                }
                            }
                            if (feature.getTypeLabel() != null && feature.getTypeLabel().length() > 0){
                                serializer.text(feature.getTypeLabel());
                            }
                            serializer.endTag(DAS_XML_NAMESPACE, "TYPE");

                            // METHOD element
                            serializer.startTag(DAS_XML_NAMESPACE, "METHOD");
                            if (feature.getMethodId() != null && feature.getMethodId().length() > 0){
                                serializer.attribute(DAS_XML_NAMESPACE, "id", feature.getMethodId());
                            }
                            if (feature.getMethodLabel() != null && feature.getMethodLabel().length() > 0){
                                serializer.text(feature.getMethodLabel());
                            }
                            serializer.endTag(DAS_XML_NAMESPACE, "METHOD");

                            // START element
                            serializer.startTag(DAS_XML_NAMESPACE, "START");
                            serializer.text(Integer.toString(feature.getStartCoordinate()));
                            serializer.endTag(DAS_XML_NAMESPACE, "START");

                            // END element
                            serializer.startTag(DAS_XML_NAMESPACE, "END");
                            serializer.text(Integer.toString(feature.getStopCoordinate()));
                            serializer.endTag(DAS_XML_NAMESPACE, "END");

                            // SCORE element
                            serializer.startTag(DAS_XML_NAMESPACE, "SCORE");
                            serializer.text ((feature.getScore() == null) ? "-" : Double.toString(feature.getScore()));
                            serializer.endTag(DAS_XML_NAMESPACE, "SCORE");

                            // ORIENTATION element
                            serializer.startTag(DAS_XML_NAMESPACE, "ORIENTATION");
                            serializer.text (feature.getOrientation());
                            serializer.endTag(DAS_XML_NAMESPACE, "ORIENTATION");

                            // PHASE element
                            serializer.startTag(DAS_XML_NAMESPACE, "PHASE");
                            serializer.text (feature.getPhase());
                            serializer.endTag(DAS_XML_NAMESPACE, "PHASE");

                            // NOTE elements
                            serializeFeatureNoteElements(feature.getNotes(), serializer);

                            // LINK elements
                            serializeFeatureLinkElements(feature.getLinks(), serializer);

                            // TARGET elements
                            serializeFeatureTargetElements(feature.getTargets(), serializer);

                            // GROUP elements
                            if (feature.getGroups() != null){
                                for (DasGroup group : feature.getGroups()){
                                    serializer.startTag(DAS_XML_NAMESPACE, "GROUP");
                                    serializer.attribute(DAS_XML_NAMESPACE, "id", group.getGroupId());
                                    if (group.getGroupLabel() != null && group.getGroupLabel().length() > 0){
                                        serializer.attribute(DAS_XML_NAMESPACE, "label", group.getGroupLabel());
                                    }
                                    if (group.getGroupType() != null && group.getGroupType().length() > 0){
                                        serializer.attribute(DAS_XML_NAMESPACE, "type", group.getGroupType());
                                    }
                                    // GROUP/NOTE elements
                                    serializeFeatureNoteElements(group.getNotes(), serializer);

                                    // GROUP/LINK elements
                                    serializeFeatureLinkElements(group.getLinks(), serializer);

                                    // GROUP/TARGET elements
                                    serializeFeatureTargetElements(group.getTargets(), serializer);

                                    serializer.endTag(DAS_XML_NAMESPACE, "GROUP");
                                }
                            }

                            serializer.endTag(DAS_XML_NAMESPACE, "FEATURE");
                        }
                    }
                    serializer.endTag(DAS_XML_NAMESPACE, "SEGMENT");
                }
            }
            serializer.endTag(DAS_XML_NAMESPACE, "GFF");
            serializer.endTag(DAS_XML_NAMESPACE, "DASGFF");

            serializer.flush();
        }
        finally{
            if (out != null){
                out.close();
            }
        }
    }

    private void serializeFeatureNoteElements(Collection<String> notes, XmlSerializer serializer) throws IOException {
        if (notes != null){
            for (String note : notes){
                serializer.startTag(DAS_XML_NAMESPACE, "NOTE");
                serializer.text (note);
                serializer.endTag(DAS_XML_NAMESPACE, "NOTE");
            }
        }
    }

    private void serializeFeatureLinkElements(Map<URL, String> links, XmlSerializer serializer) throws IOException {
        if (links != null){
            for (URL url : links.keySet()){
                serializer.startTag(DAS_XML_NAMESPACE, "LINK");
                serializer.attribute(DAS_XML_NAMESPACE, "href", url.toString());
                String linkText = links.get(url);
                if (linkText != null && linkText.length() > 0){
                    serializer.text(linkText);
                }
                serializer.endTag(DAS_XML_NAMESPACE, "LINK");
            }
        }
    }

    private void serializeFeatureTargetElements(Collection<DasTarget> targets, XmlSerializer serializer) throws IOException {
        if (targets != null){
            for (DasTarget target : targets){
                serializer.startTag(DAS_XML_NAMESPACE, "TARGET");
                serializer.attribute(DAS_XML_NAMESPACE, "id", target.getTargetId());
                serializer.attribute(DAS_XML_NAMESPACE, "start", Integer.toString(target.getStartCoordinate()));
                serializer.attribute(DAS_XML_NAMESPACE, "stop", Integer.toString(target.getStopCoordinate()));
                if (target.getTargetName() != null && target.getTargetName().length() > 0){
                    serializer.text(target.getTargetName());
                }
                serializer.endTag(DAS_XML_NAMESPACE, "TARGET");
            }
        }
    }

    private void entryPointsCommand(HttpServletRequest request, HttpServletResponse response, DataSourceConfiguration dsnConfig, String queryString)
            throws XmlPullParserException, IOException, DataSourceException, UnimplementedFeatureException {

        if (dsnConfig.getDataSource() instanceof ReferenceDataSource){
            // Fine - process command.
            ReferenceDataSource refDsn = (ReferenceDataSource) dsnConfig.getDataSource();
            Collection<DasEntryPoint> entryPoints = refDsn.getEntryPoints();
            // Check that an entry point version has been set.
            if (refDsn.getEntryPointVersion() == null){
                throw new DataSourceException("The dsn " + dsnConfig.getId() + "is returning null for the entry point version, which is invalid.");
            }
            // Looks like all is OK.
            writeHeader (request, response, STATUS_200_OK, true);
            //OK, got our entry points, so write out the XML.
            XmlSerializer serializer;
            serializer = PULL_PARSER_FACTORY.newSerializer();
            BufferedWriter out = null;
            try{
                out = getResponseWriter(request, response);
                serializer.setOutput(out);
                serializer.setProperty(INDENTATION_PROPERTY, INDENTATION_PROPERTY_VALUE);
                serializer.startDocument(null, false);
                serializer.text("\n");
                serializer.docdecl(" DASEP SYSTEM \"http://www.biodas.org/dtd/dasep.dtd\"");
                serializer.text("\n");

                // Rest of the XML.
                serializer.startTag(DAS_XML_NAMESPACE, "DASEP");
                serializer.startTag(DAS_XML_NAMESPACE, "ENTRY_POINTS");
                serializer.attribute(DAS_XML_NAMESPACE, "href", buildRequestHref(request));
                serializer.attribute(DAS_XML_NAMESPACE, "version", refDsn.getEntryPointVersion());

                // Now for the individual segments.
                for (DasEntryPoint entryPoint : entryPoints){
                    if (entryPoint != null){
                        serializer.startTag(DAS_XML_NAMESPACE, "SEGMENT");
                        serializer.attribute(DAS_XML_NAMESPACE, "id", entryPoint.getSegmentId());
                        serializer.attribute(DAS_XML_NAMESPACE, "start", Integer.toString(entryPoint.getStartCoordinate()));
                        serializer.attribute(DAS_XML_NAMESPACE, "stop", Integer.toString(entryPoint.getStopCoordinate()));
                        if (entryPoint.getType() != null && entryPoint.getType().length() > 0){
                            serializer.attribute(DAS_XML_NAMESPACE, "type", entryPoint.getType());
                        }
                        serializer.attribute(DAS_XML_NAMESPACE, "orientation", entryPoint.getOrientation().toString());
                        if (entryPoint.hasSubparts()){
                            serializer.attribute(DAS_XML_NAMESPACE, "subparts", "yes");
                        }
                        if (entryPoint.getDescription() != null && entryPoint.getDescription().length() > 0){
                            serializer.text(entryPoint.getDescription());
                        }
                        serializer.endTag(DAS_XML_NAMESPACE, "SEGMENT");
                    }
                }
                serializer.endTag(DAS_XML_NAMESPACE, "ENTRY_POINTS");
                serializer.endTag(DAS_XML_NAMESPACE, "DASEP");

                serializer.flush();
            }
            finally{
                if (out != null){
                    out.close();
                }
            }
        }
        else {
            // Not a reference source.
            throw new UnimplementedFeatureException("An attempt to request entry_point information from an annotation server has been detected.");
        }
    }



    private void sequenceCommand(HttpServletRequest request, HttpServletResponse response, DataSourceConfiguration dsnConfig, String queryString)
            throws XmlPullParserException, IOException, DataSourceException, UnimplementedFeatureException,
            BadReferenceObjectException, BadCommandArgumentsException, CoordinateErrorException {
        // Is this a reference source?
        if (dsnConfig.getDataSource() instanceof ReferenceDataSource){
            // Fine - process command.
            Collection<SequenceReporter> sequences = getSequences(dsnConfig, queryString);
            // Got some sequences, so all is OK.
            writeHeader (request, response, STATUS_200_OK, true);
            // Build the XML.
            XmlSerializer serializer;
            serializer = PULL_PARSER_FACTORY.newSerializer();
            BufferedWriter out = null;
            try{
                out = getResponseWriter(request, response);
                serializer.setOutput(out);
                serializer.setProperty(INDENTATION_PROPERTY, INDENTATION_PROPERTY_VALUE);
                serializer.startDocument(null, false);
                serializer.text("\n");
                serializer.docdecl(" DASSEQUENCE SYSTEM \"http://www.biodas.org/dtd/dassequence.dtd\"");
                serializer.text("\n");
                // Now the body of the DASDNA xml.
                serializer.startTag (DAS_XML_NAMESPACE, "DASSEQUENCE");
                for (SequenceReporter sequenceReporter : sequences){
                    serializer.startTag(DAS_XML_NAMESPACE, "SEQUENCE");
                    serializer.attribute(DAS_XML_NAMESPACE, "id", sequenceReporter.getSegmentName());
                    serializer.attribute(DAS_XML_NAMESPACE, "start", Integer.toString(sequenceReporter.getStart()));
                    serializer.attribute(DAS_XML_NAMESPACE, "stop", Integer.toString(sequenceReporter.getStop()));
                    serializer.attribute(DAS_XML_NAMESPACE, "moltype", sequenceReporter.getSequenceMoleculeType());
                    serializer.attribute(DAS_XML_NAMESPACE, "version", sequenceReporter.getSequenceVersion());
                    serializer.text(sequenceReporter.getSequenceString());
                    serializer.endTag(DAS_XML_NAMESPACE, "SEQUENCE");
                }
                serializer.endTag (DAS_XML_NAMESPACE, "DASSEQUENCE");
            }
            finally{
                if (out != null){
                    out.close();
                }
            }
        }
        else {
            // Not a reference source.
            throw new UnimplementedFeatureException("An attempt to request sequence information from an anntation server has been detected.");
        }
    }


    /**
     * Helper method used by both the featuresCommand and typesCommand.
     * @param dsnConfig
     * @param requestedSegments
     * @return
     * @throws DataSourceException
     */
    private Collection<FeaturesReporter> getFeatureCollection(DataSourceConfiguration dsnConfig,
                                                              List <SegmentQuery> requestedSegments,
                                                              boolean unknownSegmentsHandled
                                )
            throws DataSourceException, BadReferenceObjectException {
        List<FeaturesReporter> featuresReporterList = new ArrayList<FeaturesReporter>(requestedSegments.size());
        AnnotationDataSource dataSource = dsnConfig.getDataSource();
        for (SegmentQuery segmentQuery : requestedSegments){
            try{
                if (segmentQuery.getStartCoordinate() == null){
                    // Easy request - just want all the features on the segment.
                    featuresReporterList.add(new FoundFeaturesReporter(dataSource.getFeatures(segmentQuery.getSegmentId())));
                }
                else {
                    // Restricted to coordinates.
                    DasAnnotatedSegment annotatedSegment;
                    if (dataSource instanceof RangeHandlingAnnotationDataSource){
                        annotatedSegment = ((RangeHandlingAnnotationDataSource)dataSource).getFeatures(
                                segmentQuery.getSegmentId(),
                                segmentQuery.getStartCoordinate(),
                                segmentQuery.getStopCoordinate());
                    }
                    else if (dataSource instanceof RangeHandlingReferenceDataSource){
                        annotatedSegment = ((RangeHandlingReferenceDataSource)dataSource).getFeatures(
                                segmentQuery.getSegmentId(),
                                segmentQuery.getStartCoordinate(),
                                segmentQuery.getStopCoordinate());
                    }
                    else {
                        annotatedSegment = dataSource.getFeatures(
                                segmentQuery.getSegmentId());
                    }
                    featuresReporterList.add(new FoundFeaturesReporter(annotatedSegment, segmentQuery));
                }
            } catch (BadReferenceObjectException broe) {
                if (unknownSegmentsHandled){
                    featuresReporterList.add(new UnknownSegmentReporter(segmentQuery));
                }
                else {
                    throw broe;
                }
            } catch (CoordinateErrorException e) {
                if (unknownSegmentsHandled){
                    featuresReporterList.add(new UnknownSegmentReporter(segmentQuery));
                }
                else {
                    throw e;
                }
            }
        }
        return featuresReporterList;
    }

    /**
     * Helper method used by both the dnaCommand and the sequenceCommand
     * @param dsnConfig
     * @param queryString
     * @return
     * @throws BadReferenceObjectException
     * @throws CoordinateErrorException
     * @throws DataSourceException
     * @throws BadCommandArgumentsException
     */
    private Collection<SequenceReporter> getSequences(DataSourceConfiguration dsnConfig, String queryString)
            throws BadReferenceObjectException, CoordinateErrorException, DataSourceException, BadCommandArgumentsException {

        ReferenceDataSource refDsn = (ReferenceDataSource) dsnConfig.getDataSource();
        if (refDsn == null){
            throw new DataSourceException ("An attempt has been made to retrieve a sequenceString from datasource " + dsnConfig.getId() + " however the DataSource object is null.");
        }
        Collection<SequenceReporter> sequenceCollection = new ArrayList<SequenceReporter>();
        // Parse the queryString to retrieve all the DasSequence objects.
        if (queryString == null || queryString.length() == 0){
            throw new BadCommandArgumentsException("Expecting at least one reference in the query string, but found nothing.");
        }
        // Split on the ; (delineates separate references in the query string)
        String[] referenceStrings = queryString.split(";");
        for (String referenceString : referenceStrings){
            Matcher referenceStringMatcher = SEGMENT_RANGE_PATTERN.matcher(referenceString);
            if (referenceStringMatcher.find()){
                SegmentQuery segmentQuery = new SegmentQuery(referenceStringMatcher);
                if (segmentQuery.getStartCoordinate() != null){
                    // Getting a restricted sequenceString - and the data source will handle the restriction.
                    DasSequence sequence;
                    if (refDsn instanceof RangeHandlingReferenceDataSource){
                        sequence = ((RangeHandlingReferenceDataSource)refDsn).getSequence(
                                segmentQuery.getSegmentId(),
                                segmentQuery.getStartCoordinate(),
                                segmentQuery.getStopCoordinate()
                        );
                    }
                    else {
                        sequence = refDsn.getSequence(segmentQuery.getSegmentId());
                    }
                    if (sequence == null) throw new BadReferenceObjectException(segmentQuery.getSegmentId(), "Segment cannot be found.");
                    sequenceCollection.add (new SequenceReporter(
                            sequence,
                            segmentQuery));
                }
                else {
                    // Request for a complete sequenceString
                    DasSequence sequence = refDsn.getSequence(segmentQuery.getSegmentId());
                    if (sequence == null) throw new BadReferenceObjectException(segmentQuery.getSegmentId(), "Segment cannot be found.");
                    sequenceCollection.add (new SequenceReporter(sequence));
                }
            }
            else {
                throw new BadCommandArgumentsException("The query string format is not recognized.");
            }
        }
        if (sequenceCollection.size() ==0){
            // The query string did not include any segment references.
            throw new BadCommandArgumentsException("The query string format is not recognized.");
        }
        return sequenceCollection;
    }


    /**
     * Writes the response header with the additional DAS Http headers.
     * @param response to which to write the headers.
     * @param status being the status to write.
     * @param request required to determine if the client will accept a compressed response
     * @param compressionAllowed to indicate if the specific response should be gzipped. (e.g. an error message with
     * no content should not set the compressed header.)
     */
    private void writeHeader (HttpServletRequest request, HttpServletResponse response, String status, boolean compressionAllowed){
        response.setHeader(HEADER_KEY_X_DAS_VERSION, HEADER_VALUE_DAS_VERSION);
        response.setHeader(HEADER_KEY_X_DAS_CAPABILITIES, HEADER_VALUE_CAPABILITIES);
        response.setHeader(HEADER_KEY_X_DAS_STATUS, status);
        if (compressionAllowed && compressResponse (request)){
            response.setHeader(ENCODING_RESPONSE_HEADER_KEY, ENCODING_GZIPPED);
        }
    }

    /**
     * Returns a PrintWriter for the response. First checks if the output should / can be
     * gzipped. If so, wraps the OutputStream in a GZIPOutputStream and then returns
     * a PrintWriter to this.
     * @param request the HttpServletRequest, needed to check the capabilities of the
     * client.
     * @param response from which the OutputStream is obtained
     * @return a PrintWriter that will either produce plain or gzipped output.
     * @throws IOException due to a problem with initiating the output stream or writer.
     */
    private BufferedWriter getResponseWriter (HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (compressResponse(request)){
            // Wrap the response writer in a Zipstream.
            GZIPOutputStream zipStream = new GZIPOutputStream(response.getOutputStream());
            return new BufferedWriter (new PrintWriter(zipStream));
        }
        else {
            return new BufferedWriter (response.getWriter());
        }
    }

    /**
     * Checks in the configuration to see if the output should be gzipped and also
     * checks if the client can accept gzipped output.
     * @param request being the HttpServletRequest, to allow a check of the client capabilities to be checked.
     * @return a boolean indicating if the response should be compressed.
     */
    private boolean compressResponse (HttpServletRequest request){
        String clientEncodingAbility = request.getHeader(ENCODING_REQUEST_HEADER_KEY);
        return DATA_SOURCE_MANAGER.getServerConfiguration().getGlobalConfiguration().isGzipped()
                && clientEncodingAbility != null
                && clientEncodingAbility.contains(ENCODING_GZIPPED);
    }


    private String buildRequestHref(HttpServletRequest request) {
        StringBuffer requestURL = new StringBuffer(DATA_SOURCE_MANAGER.getServerConfiguration().getGlobalConfiguration().getBaseURL());
        String requestURI = request.getRequestURI();
        // The /das/ part of the URL comes from the baseurl configuration, so need to add on the request after this point.
        requestURL.append (requestURI.substring(5 + requestURI.indexOf("/das/")));
        String queryString = request.getQueryString();
        if (queryString != null && queryString.length() > 0){
            requestURL.append ('?')
                    .append (queryString);
        }
        return requestURL.toString();
    }
}
