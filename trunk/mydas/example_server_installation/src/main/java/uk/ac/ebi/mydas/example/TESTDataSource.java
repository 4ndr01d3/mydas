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

package uk.ac.ebi.mydas.example;

import uk.ac.ebi.mydas.controller.DataSourceConfiguration;
import uk.ac.ebi.mydas.exceptions.DataSourceException;
import uk.ac.ebi.mydas.exceptions.BadReferenceObjectException;
import uk.ac.ebi.mydas.exceptions.UnimplementedFeatureException;
import uk.ac.ebi.mydas.model.*;
import uk.ac.ebi.mydas.datasource.ReferenceDataSource;

import javax.servlet.ServletContext;
import java.util.*;
import java.net.URL;
import java.net.MalformedURLException;

/**
 * Created Using IntelliJ IDEA.
 * Date: 09-May-2007
 * Time: 14:46:59
 *
 * @author Phil Jones, EMBL-EBI, pjones@ebi.ac.uk
 *
 * This test data source is used in conjunction with the WebIntegrationTest test class to test
 * the running web application.
 */
public class TESTDataSource implements ReferenceDataSource {

    ServletContext svCon;
    Map<String, String> globalParameters;
    DataSourceConfiguration config;
    /**
     * This method is called by the {@link uk.ac.ebi.mydas.controller.MydasServlet} class at Servlet initialisation.
     * <p/>
     * The AnnotationDataSource is passed the servletContext, a handle to globalParameters in the
     * form of a Map &lt;String, String&gt; and a DataSourceConfiguration object.
     * <p/>
     * The latter two parameters contain all of the pertinent information in the
     * ServerConfig.xml file relating to the server as a whole and specifically to
     * this data source.  This mechanism allows the datasource author to set up
     * required configuration in one place, including AnnotationDataSource specific configuration.
     * <p/>
     * <bold>It is highly desirable for the implementation to test itself in this init method and throw
     * a DataSourceException if it fails, e.g. to attempt to get a Connection to a database
     * and read a record.</bold>
     *
     * @param servletContext   being the ServletContext of the servlet container that the
     *                         Mydas servlet is running in.
     * @param globalParameters being a Map &lt;String, String&gt; of keys and values
     *                         as defined in the ServerConfig.xml file.
     * @param dataSourceConfig containing the pertinent information frmo the ServerConfig.xml
     *                         file for this datasource, including (optionally) a Map of datasource specific configuration.
     * @throws uk.ac.ebi.mydas.exceptions.DataSourceException
     *          should be thrown if there is any
     *          fatal problem with loading this data source.  <bold>It is highly desirable for the implementation to test itself in this init method and throw
     *          a DataSourceException if it fails, e.g. to attempt to get a Connection to a database
     *          and read a record.</bold>
     */
    public void init(ServletContext servletContext, Map<String, String> globalParameters, DataSourceConfiguration dataSourceConfig) throws DataSourceException {
        this.svCon = servletContext;
        this.globalParameters = globalParameters;
        this.config = dataSourceConfig;
    }

    public DataSourceConfiguration getConfiguration() {
        return config;
    }

    /**
     * This method is called when the DAS server is shut down and should be used
     * to clean up resources such as database connections as required.
     */
    public void destroy() {
        // In this case, nothing to do.
    }

    /**
     * This method returns a List of DasFeature objects, describing the Features
     * of the segmentReference passed in as argument.
     *
     * @param segmentReference being the reference of the segment requested in the DAS request (not including
     *                         start and stop coordinates)
     *                         <p/>
     *                         If your datasource implements only this interface,
     *                         the MydasServlet will handle restricting the features returned to
     *                         the start / stop coordinates in the request and you will only need to
     *                         implement this method to return Features.  If on the other hand, your data source
     *                         includes massive segments, you may wish to implement the {@link uk.ac.ebi.mydas.datasource.RangeHandlingAnnotationDataSource}
     *                         interface.  It will then be the responsibility of your AnnotationDataSource plugin to
     *                         restrict the features returned for the requested range.
     * @return a List of DasFeature objects.
     * @throws uk.ac.ebi.mydas.exceptions.BadReferenceObjectException
     *
     * @throws uk.ac.ebi.mydas.exceptions.DataSourceException
     *
     */
    public DasAnnotatedSegment getFeatures(String segmentReference) throws BadReferenceObjectException, DataSourceException {
        try{
            if (segmentReference.equals ("one")){
                Collection<DasFeature> oneFeatures = new ArrayList<DasFeature>(2);
                DasTarget target = new DasTarget("oneTargetId", 20, 30, "oneTargetName");
                DasGroup group = new DasGroup(
                        "oneGroupId",
                        "one Group Label",
                        "onegrouptype",
                        Collections.singleton("A note on the group for reference one."),
                        Collections.singletonMap(new URL("http://code.google.com/p/mydas/"), "mydas project home page."),
                        Collections.singleton(target)
                );
                oneFeatures.add(new DasFeature(
                        "oneFeatureIdOne",
                        "one Feature Label One",
                        "oneFeatureTypeIdOne",
                        "oneFeatureCategoryOne",
                        "one Feature Type Label One",
                        "oneFeatureMethodIdOne",
                        "one Feature Method Label One",
                        5,
                        10,
                        123.45,
                        DasFeature.ORIENTATION_NOT_APPLICABLE,
                        DasFeature.PHASE_NOT_APPLICABLE,
                        Collections.singleton("This is a note relating to feature one of segment one."),
                        Collections.singletonMap(new URL("http://code.google.com/p/mydas/"), "mydas project home page."),
                        Collections.singleton(target),
                        Collections.singleton(group)
                ));
                oneFeatures.add(new DasFeature(
                        "oneFeatureIdTwo",
                        "one Feature Label Two",
                        "oneFeatureTypeIdTwo",
                        "oneFeatureCategoryTwo",
                        "one Feature Type Label Two",
                        "oneFeatureMethodIdTwo",
                        "one Feature Method Label Two",
                        18,
                        25,
                        96.3,
                        DasFeature.ORIENTATION_NOT_APPLICABLE,
                        DasFeature.PHASE_NOT_APPLICABLE,
                        Collections.singleton("This is a note relating to feature two of segment one."),
                        Collections.singletonMap(new URL("http://code.google.com/p/mydas/"), "mydas project home page."),
                        null,
                        null
                ));
                return new DasAnnotatedSegment("one", 1, 34, "Up-to-date", "one_label", oneFeatures);
            }
            else if (segmentReference.equals("two")){
                Collection<DasFeature> twoFeatures = new ArrayList<DasFeature>(2);
                twoFeatures.add(new DasFeature(
                        "twoFeatureIdOne",
                        "two Feature Label One",
                        "twoFeatureTypeIdOne",
                        "twoFeatureCategoryOne",
                        "two Feature Type Label One",
                        "twoFeatureMethodIdOne",
                        "two Featur eMethod Label One",
                        9,
                        33,
                        1000.01,
                        DasFeature.ORIENTATION_SENSE_STRAND,
                        DasFeature.PHASE_READING_FRAME_0,
                        Collections.singleton("This is a note relating to feature one of segment two."),
                        Collections.singletonMap(new URL("http://code.google.com/p/mydas/"), "mydas project home page."),
                        null,
                        null
                ));
                return new DasAnnotatedSegment("two", 1, 48, "Up-to-date", "two_label", twoFeatures);
            }
            else throw new BadReferenceObjectException(segmentReference, "Not found");
        }
        catch (MalformedURLException e) {
            throw new DataSourceException("Tried to create an invalid URL for a LINK element.", e);
        }
    }

    /**
     * <b>For some Datasources, especially ones with many entry points, this method may be hard or impossible
     * to implement.  If this is the case, you should just throw an {@link uk.ac.ebi.mydas.exceptions.UnimplementedFeatureException} as your
     * implementation of this method, so that a suitable error HTTP header
     * (X-DAS-Status: 501 Unimplemented feature) is returned to the DAS client as
     * described in the DAS 1.53 protocol.</b><br/><br/>
     * <p/>
     * This method is used by the features command when no segments are included, but feature_id and / or
     * group_id filters have been included, to meet the following specification:<br/><br/>
     * <p/>
     * "<b>feature_id</b> (zero or more; new in 1.5)<br/>
     * Instead of, or in addition to, <b>segment</b> arguments, you may provide one or more <b>feature_id</b>
     * arguments, whose values are the identifiers of particular features.  If the server supports this operation,
     * it will translate the feature ID into the segment(s) that strictly enclose them and return the result in
     * the <i>features</i> response.  It is possible for the server to return multiple segments if the requested
     * feature is present in multiple locations.
     * <b>group_id</b> (zero or more; new in 1.5)<br/>
     * The <b>group_id</b> argument, is similar to <b>feature_id</b>, but retrieves segments that contain
     * the indicated feature group."  (Direct quote from the DAS 1.53 specification, available from
     * <a href="http://biodas.org/documents/spec.html">http://biodas.org/documents/spec.html</a>.)
     * <p/>
     * Note that if segments are included in the request, this method is not used, so feature_id and group_id
     * filters accompanying a list of segments will work, even if your implementation of this method throws an
     * {@link uk.ac.ebi.mydas.exceptions.UnimplementedFeatureException}.
     *
     * @param featureIdCollection a Collection&lt;String&gt; of feature_id values included in the features command / request.
     *                            May be a <code>java.util.Collections.EMPTY_LIST</code> but will <b>not</b> be null.
     * @param groupIdCollection   a Collection&lt;String&gt; of group_id values included in the features command / request.
     *                            May be a <code>java.util.Collections.EMPTY_LIST</code> but will <b>not</b> be null.
     * @return A Collection of {@link uk.ac.ebi.mydas.model.DasAnnotatedSegment} objects. These describe the segments that is annotated, limited
     *         to the information required for the /DASGFF/GFF/SEGMENT element.  Each References a Collection of
     *         DasFeature objects.   Note that this is a basic Collection - this gives you complete control over the details
     *         of the Collection type - so you can create your own comparators etc.
     * @throws uk.ac.ebi.mydas.exceptions.DataSourceException
     *          should be thrown if there is any
     *          fatal problem with loading this data source.  <bold>It is highly desirable for the implementation to test itself in this init method and throw
     *          a DataSourceException if it fails, e.g. to attempt to get a Connection to a database
     *          and read a record.</bold>
     * @throws uk.ac.ebi.mydas.exceptions.UnimplementedFeatureException
     *          Throw this if you cannot
     *          provide a working implementation of this method.
     */
    public Collection<DasAnnotatedSegment> getFeatures(Collection<String> featureIdCollection, Collection<String> groupIdCollection) throws UnimplementedFeatureException, DataSourceException {
        throw new UnimplementedFeatureException("\"This test class, it is not ready yet!\" (in a Swedish accent)");
    }

    /**
     * Returns a Collection of {@link DasEntryPoint} objects to implement the entry_point command.
     *
     * @return a Collection of {@link DasEntryPoint} objects
     * @throws uk.ac.ebi.mydas.exceptions.DataSourceException
     *          to encapsulate any exceptions thrown by the datasource
     *          and allow the {@link uk.ac.ebi.mydas.controller.MydasServlet} to return a decent error header to the client.
     */
    public Collection<DasEntryPoint> getEntryPoints() throws DataSourceException {
        List<DasEntryPoint> entryPoints = new ArrayList<DasEntryPoint>();
        entryPoints.add (new DasEntryPoint("one", 1, 34, "Protein", null, "Its a protein!", false));
        entryPoints.add (new DasEntryPoint("two", 1, 48, "DNA", DasEntryPoint.POSITIVE_ORIENTATION, "Its a chromosome!", false));
        return entryPoints;
    }

    /**
     * Extends the {@link uk.ac.ebi.mydas.datasource.ReferenceDataSource} inteface to allow the creation of an Annotation
     * data source.  The only significant difference is that a Reference data source can also
     * serve the sequenceString of the requested segment.
     *
     * @param segmentReference being the name of the sequenceString being requested.
     * @return a {@link DasSequence} object, holding the sequenceString and start / end coordinates of the sequenceString.
     * @throws uk.ac.ebi.mydas.exceptions.BadReferenceObjectException
     *          to inform the {@link uk.ac.ebi.mydas.controller.MydasServlet} that the
     *          segment requested is not available from this DataSource.
     * @throws uk.ac.ebi.mydas.exceptions.DataSourceException
     *          to encapsulate any exceptions thrown by the datasource
     *          and allow the MydasServlet to return a decent error header to the client.
     */
    public DasSequence getSequence(String segmentReference) throws BadReferenceObjectException, DataSourceException {
        if (segmentReference.equals ("one")){
            return new DasSequence("one", "FFDYASTDFYASDFAUFDYFVSHCVYTDASVCYT", 1, "Up-to-date", DasSequence.TYPE_PROTEIN);
        }
        else if (segmentReference.equals("two")){
            return new DasSequence("two", "cgatcatcagctacgtacgatcagtccgtacgatcgatcagcatcaca", 1, "Up-to-date", DasSequence.TYPE_DNA);
        }
        else throw new BadReferenceObjectException(segmentReference, "Not found");
    }

    /**
     * Returns the value to be returned from the entry_points command, specifically
     * the /DASEP/ENTRY_POINTS/@version attribute.
     * <p/>
     * This is a <b>mandatory</b> value so you must ensure that this method does not
     * return null or an empty String. (The {@link uk.ac.ebi.mydas.controller.MydasServlet} will return an error to the
     * client if you do).
     *
     * @return a non-null, non-zero length String, being the version number of the
     *         entry points / datasource.
     * @throws uk.ac.ebi.mydas.exceptions.DataSourceException
     *          to encapsulate any exceptions thrown by the datasource
     *          and allow the {@link uk.ac.ebi.mydas.controller.MydasServlet} to return a decent error header to the client.
     */
    public String getEntryPointVersion() throws DataSourceException {
        return "Version 1.1";
    }
}
