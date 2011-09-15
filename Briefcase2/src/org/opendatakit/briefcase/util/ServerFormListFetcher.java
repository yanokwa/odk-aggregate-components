package org.opendatakit.briefcase.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.protocol.HttpContext;
import org.bushe.swing.event.EventBus;
import org.javarosa.xform.parse.XFormParser;
import org.kxml2.io.KXmlParser;
import org.kxml2.io.KXmlSerializer;
import org.kxml2.kdom.Document;
import org.kxml2.kdom.Element;
import org.opendatakit.briefcase.model.FormStatus;
import org.opendatakit.briefcase.model.FormStatusEvent;
import org.opendatakit.briefcase.model.LocalFormDefinition;
import org.opendatakit.briefcase.model.RemoteFormDefinition;
import org.opendatakit.briefcase.model.ServerConnectionInfo;
import org.xmlpull.v1.XmlPullParser;

public class ServerFormListFetcher {
	private static final String FETCH_FAILED_DETAILED_REASON = "Fetch of %1$s failed. Detailed reason: ";

	private static final Log logger = LogFactory.getLog(ServerFormListFetcher.class);

    private static final String NAMESPACE_OPENROSA_ORG_XFORMS_XFORMS_MANIFEST =
        "http://openrosa.org/xforms/xformsManifest";

    private static final String NAMESPACE_OPENROSA_ORG_XFORMS_XFORMS_LIST =
        "http://openrosa.org/xforms/xformsList";
    
    private static final String NAMESPACE_OPENDATAKIT_ORG_SUBMISSIONS = "http://opendatakit.org/submissions";

    private static final String BAD_OPENROSA_FORMLIST = 
    		"The server has not provided an available-forms document compliant with the OpenRosa version 1.0 standard.";
    
    private static final String BAD_LEGACY_FORMLIST = 
    	"The server has not provided an available-forms document compatible with Aggregate 0.9.x.";

    private static final String MD5_COLON_PREFIX = "md5:";

	private static final CharSequence HTTP_CONTENT_TYPE_TEXT_XML = "text/xml";

	private static final CharSequence HTTP_CONTENT_TYPE_APPLICATION_XML = "application/xml";

	private static final int MAX_ENTRIES = 2;

	ServerConnectionInfo serverInfo;

	public static class FormListException extends Exception {

		/**
		 * 
		 */
		private static final long serialVersionUID = -2443850446028219296L;
		
		FormListException(String message) {
			super(message);
		}
	};
	
	public  static class SubmissionListException extends Exception {
		
		/**
		 * 
		 */
		private static final long serialVersionUID = 8707375089373674335L;

		SubmissionListException(String message) {
			super(message);
		}
	}
	
	public  static class SubmissionDownloadException extends Exception {
		
		/**
		 * 
		 */
		private static final long serialVersionUID = 8717375089373674335L;

		SubmissionDownloadException(String message) {
			super(message);
		}
	}

	public static class DownloadException extends Exception {
		/**
		 * 
		 */
		private static final long serialVersionUID = 3142210034175698950L;

		DownloadException(String message) {
			super(message);
		}
	}
	

    private static class DocumentFetchResult {
        public final String errorMessage;
        public final Document doc;
        public final boolean isOpenRosaResponse;


        DocumentFetchResult(String msg) {
            errorMessage = msg;
            doc = null;
            isOpenRosaResponse = false;
        }


        DocumentFetchResult(Document doc, boolean isOpenRosaResponse) {
            errorMessage = null;
            this.doc = doc;
            this.isOpenRosaResponse = isOpenRosaResponse;
        }
    }

    private boolean isXformsListNamespacedElement(Element e) {
        return e.getNamespace().equalsIgnoreCase(NAMESPACE_OPENROSA_ORG_XFORMS_XFORMS_LIST);
    }


    private boolean isXformsManifestNamespacedElement(Element e) {
        return e.getNamespace().equalsIgnoreCase(NAMESPACE_OPENROSA_ORG_XFORMS_XFORMS_MANIFEST);
    }


	ServerFormListFetcher(ServerConnectionInfo serverInfo) throws IOException {
		this.serverInfo = serverInfo;
	}
	
	public List<RemoteFormDefinition> parseFormListResponse(Document formListDoc) throws FormListException {
        // This gets a list of available forms from the specified server.
		List<RemoteFormDefinition> formList = new ArrayList<RemoteFormDefinition>();

        if (serverInfo.isOpenRosaServer()) {
            // Attempt OpenRosa 1.0 parsing
            Element xformsElement = formListDoc.getRootElement();
            if (!xformsElement.getName().equals("xforms")) {
            	logger.error("Parsing OpenRosa reply -- root element is not <xforms> :"
                        + xformsElement.getName());
            	throw new FormListException(BAD_OPENROSA_FORMLIST);
            }
            String namespace = xformsElement.getNamespace();
            if (!isXformsListNamespacedElement(xformsElement)) {
            	logger.error("Parsing OpenRosa reply -- root element namespace is incorrect:"
                        + namespace);
            	throw new FormListException(BAD_OPENROSA_FORMLIST);
            }
            int nElements = xformsElement.getChildCount();
            for (int i = 0; i < nElements; ++i) {
                if (xformsElement.getType(i) != Element.ELEMENT) {
                    // e.g., whitespace (text)
                    continue;
                }
                Element xformElement = (Element) xformsElement.getElement(i);
                if (!isXformsListNamespacedElement(xformElement)) {
                    // someone else's extension?
                    continue;
                }
                String name = xformElement.getName();
                if (!name.equalsIgnoreCase("xform")) {
                    // someone else's extension?
                    continue;
                }

                // this is something we know how to interpret
                String formId = null;
                String formName = null;
                String majorMinorVersion = null;
                String description = null;
                String downloadUrl = null;
                String manifestUrl = null;
                // don't process descriptionUrl
                int fieldCount = xformElement.getChildCount();
                for (int j = 0; j < fieldCount; ++j) {
                    if (xformElement.getType(j) != Element.ELEMENT) {
                        // whitespace
                        continue;
                    }
                    Element child = xformElement.getElement(j);
                    if (!isXformsListNamespacedElement(child)) {
                        // someone else's extension?
                        continue;
                    }
                    String tag = child.getName();
                    if (tag.equals("formID")) {
                        formId = XFormParser.getXMLText(child, true);
                        if (formId != null && formId.length() == 0) {
                            formId = null;
                        }
                    } else if (tag.equals("name")) {
                        formName = XFormParser.getXMLText(child, true);
                        if (formName != null && formName.length() == 0) {
                            formName = null;
                        }
                    } else if (tag.equals("majorMinorVersion")) {
                        majorMinorVersion = XFormParser.getXMLText(child, true);
                        if (majorMinorVersion != null && majorMinorVersion.length() == 0) {
                            majorMinorVersion = null;
                        }
                    } else if (tag.equals("descriptionText")) {
                        description = XFormParser.getXMLText(child, true);
                        if (description != null && description.length() == 0) {
                            description = null;
                        }
                    } else if (tag.equals("downloadUrl")) {
                        downloadUrl = XFormParser.getXMLText(child, true);
                        if (downloadUrl != null && downloadUrl.length() == 0) {
                            downloadUrl = null;
                        }
                    } else if (tag.equals("manifestUrl")) {
                        manifestUrl = XFormParser.getXMLText(child, true);
                        if (manifestUrl != null && manifestUrl.length() == 0) {
                            manifestUrl = null;
                        }
                    }
                }
                if (formId == null || downloadUrl == null || formName == null) {
                	logger.error("Parsing OpenRosa reply -- Forms list entry " + Integer.toString(i)
                            + " is missing one or more tags: formId, name, or downloadUrl");
                    formList.clear();
                	throw new FormListException(BAD_OPENROSA_FORMLIST);
                }
                Integer modelVersion = null;
                Integer uiVersion = null;
                try {
                    if (majorMinorVersion == null || majorMinorVersion.length() == 0) {
                        modelVersion = null;
                        uiVersion = null;
                    } else {
                        int idx = majorMinorVersion.indexOf(".");
                        if (idx == -1) {
                            modelVersion = Integer.parseInt(majorMinorVersion);
                            uiVersion = null;
                        } else {
                            modelVersion = Integer.parseInt(majorMinorVersion.substring(0, idx));
                            uiVersion =
                                (idx == majorMinorVersion.length() - 1) ? null : Integer
                                        .parseInt(majorMinorVersion.substring(idx + 1));
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    logger.error("Parsing OpenRosa reply -- Forms list entry " + Integer.toString(i)
                            + " has an invalid majorMinorVersion: " + majorMinorVersion);
                    formList.clear();
                	throw new FormListException(BAD_OPENROSA_FORMLIST);
                }
                formList.add(new RemoteFormDefinition(formName, formId, modelVersion, uiVersion,
                        downloadUrl, manifestUrl));
            }
        } else {
            // Aggregate 0.9.x mode...
            // populate HashMap with form names and urls
            Element formsElement = formListDoc.getRootElement();
            int formsCount = formsElement.getChildCount();
            for (int i = 0; i < formsCount; ++i) {
                if (formsElement.getType(i) != Element.ELEMENT) {
                    // whitespace
                    continue;
                }
                Element child = formsElement.getElement(i);
                String tag = child.getName();
                if (tag.equalsIgnoreCase("form")) {
                    String formName = XFormParser.getXMLText(child, true);
                    if (formName != null && formName.length() == 0) {
                        formName = null;
                    }
                    String downloadUrl = child.getAttributeValue(null, "url");
                    downloadUrl = downloadUrl.trim();
                    if (downloadUrl != null && downloadUrl.length() == 0) {
                        downloadUrl = null;
                    }
                    if (downloadUrl == null || formName == null) {
                        logger.error("Parsing OpenRosa reply -- Forms list entry "
                                + Integer.toString(i) + " is missing form name or url attribute");
                        formList.clear();
                    	throw new FormListException(BAD_LEGACY_FORMLIST);
                    }
                    formList.add(new RemoteFormDefinition(formName, null, null, null, 
                    		downloadUrl, null));
                }
            }
        }
        return formList;
	}

    public void downloadFiles(File briefcaseDir, List<FormStatus> formsToTransfer) throws IOException {

        // boolean error = false;
        int total = formsToTransfer.size();

        for (int i = 0; i < total; i++) {
        	FormStatus fs = formsToTransfer.get(i);
        	RemoteFormDefinition fd = (RemoteFormDefinition) fs.getFormDefinition();
        	fs.setStatusString("Fetching form definition");
            EventBus.publish(new FormStatusEvent(fs));
            try {
            	
            	File dl = this.getFormDefinitionFile(briefcaseDir, fd.getFormName());
            	commonDownloadFile(dl, fd.getDownloadUrl());
                
                if (fd.getManifestUrl() != null) {
                	File mediaDir = this.getMediaDirectory(briefcaseDir, fd.getFormName());
                    String error = downloadManifestAndMediaFiles(mediaDir, fs);
                    if (error != null) {
                    	fs.setStatusString("Error fetching form definition: " + error);
                        EventBus.publish(new FormStatusEvent(fs));
                        continue;
                    }
                }
                fs.setStatusString("preparing to retrieve instance data");
    			EventBus.publish(new FormStatusEvent(fs));

    			File formInstancesDir = this.getFormInstancesDirectory(briefcaseDir, fd.getFormName());
    			
                // TODO: pull down data files...
    			LocalFormDefinition lfd = new LocalFormDefinition(dl);
    			downloadDataFiles(formInstancesDir, lfd, fs);
    			
                fs.setFormDefinition(lfd);
            } catch (SocketTimeoutException se) {
                se.printStackTrace();
                fs.setStatusString("Communications to the server timed out. Detailed message: "
                        + se.getLocalizedMessage() + " while accessing: "
                        + fd.getDownloadUrl() + " A network login screen may be interfering with the transmission to the server." );
                EventBus.publish(new FormStatusEvent(fs));
                continue;
            } catch (Exception e) {
                e.printStackTrace();
                fs.setStatusString("Unexpected error: "
                		+ e.getLocalizedMessage() + " while accessing: "
                		+ fd.getDownloadUrl()
                        + " A network login screen may be interfering with the transmission to the server." );
                EventBus.publish(new FormStatusEvent(fs));
                continue;
            }
        }
    }


	private void downloadDataFiles(File formInstancesDir, LocalFormDefinition lfd, FormStatus fs) throws SubmissionListException, SubmissionDownloadException {
		
    	RemoteFormDefinition fd = (RemoteFormDefinition) fs.getFormDefinition();
		
    	String baseUrl = serverInfo.getUrl() + "/view/submissionList";
    	
        HttpContext localContext = serverInfo.getHttpContext();

        HttpClient httpclient = serverInfo.getHttpClient();

        String oldWebsafeCursorString = "not-empty";
        String websafeCursorString = "";
        for (;!oldWebsafeCursorString.equals(websafeCursorString);) {
	        Map<String,String> params = new HashMap<String,String>();
	        params.put("numEntries", Integer.toString(MAX_ENTRIES));
	        params.put("formId", fd.getFormId());
	        params.put("cursor", websafeCursorString);
	        String fullUrl = WebUtils.createLinkWithProperties(baseUrl, params);
	        oldWebsafeCursorString = websafeCursorString; // remember what we had...
	        DocumentFetchResult result = getXmlDocument(fullUrl, localContext,
	                httpclient, "Fetch of submission download chunk failed.  Detailed error: ", 
	                			"Fetch of submission download chunk failed.");
	        if (result.errorMessage != null) {
	            throw new SubmissionListException(result.errorMessage);
	        }

	        // and parse the document...
	        List<String> uriList = new ArrayList<String>();
            // Attempt parsing
            Element idChunkElement = result.doc.getRootElement();
            if (!idChunkElement.getName().equals("idChunk")) {
            	String msg = "Parsing submissionList reply -- root element is not <idChunk> :"
                    + idChunkElement.getName();
            	logger.error(msg);
            	throw new SubmissionListException(msg);
            }
            String namespace = idChunkElement.getNamespace();
            if (!namespace.equalsIgnoreCase(NAMESPACE_OPENDATAKIT_ORG_SUBMISSIONS) ) {
            	String msg = "Parsing submissionList reply -- root element namespace is incorrect:"
                        + namespace;
            	logger.error(msg);
            	throw new SubmissionListException(msg);
            }
            int nElements = idChunkElement.getChildCount();
            for (int i = 0; i < nElements; ++i) {
                if (idChunkElement.getType(i) != Element.ELEMENT) {
                    // e.g., whitespace (text)
                    continue;
                }
                Element subElement = (Element) idChunkElement.getElement(i);
                namespace = subElement.getNamespace();
	            if (!namespace.equalsIgnoreCase(NAMESPACE_OPENDATAKIT_ORG_SUBMISSIONS) ) {
                    // someone else's extension?
                    continue;
                }
                String name = subElement.getName();
                if ( name.equalsIgnoreCase("idList")) {
                	// parse the idList
    	            int nIdElements = subElement.getChildCount();
    	            for (int j = 0; j < nIdElements; ++j) {
    	                if (subElement.getType(j) != Element.ELEMENT) {
    	                    // e.g., whitespace (text)
    	                    continue;
    	                }
    	                Element idElement = (Element) subElement.getElement(j);
    	                namespace = idElement.getNamespace();
    		            if (!namespace.equalsIgnoreCase(NAMESPACE_OPENDATAKIT_ORG_SUBMISSIONS) ) {
    	                    // someone else's extension?
    	                    continue;
    	                }
    	                name = idElement.getName();
    	                if ( name.equalsIgnoreCase("id")) {
    	                	// gather the uri
    	                	String uri = XFormParser.getXMLText(idElement, true);
    	                	if (uri != null) {
    	                		uriList.add(uri);
                            }
    	                } else {
    	                	logger.warn("Unrecognized tag inside idList: " + name);
    	                }
    	            }
                } else if ( name.equalsIgnoreCase("resumptionCursor")) {
                	// gather the resumptionCursor
                	websafeCursorString = XFormParser.getXMLText(subElement, true);
                	if (websafeCursorString == null) {
                		websafeCursorString = "";
                    }
                } else {
                	logger.warn("Unrecognized tag inside idChunk: " + name);
                }
            }
            
            // TODO: download all the uris in the uriList
            for ( String uri : uriList ) {
            	try {
					downloadSubmission(formInstancesDir, lfd, fs, uri);
				} catch (Exception e) {
					e.printStackTrace();
					throw new SubmissionListException("Unexpected exception: " + e.getMessage());
				}
            }
        }
	}

	private static class Attachment {
		final String downloadUrl;
		final String hash;
		final String filename;
		
		Attachment(String filename, String hash, String downloadUrl) {
			this.downloadUrl = downloadUrl;
			this.hash = hash;
			this.filename = filename;
		}
	}

	private void downloadSubmission(File formInstancesDir, LocalFormDefinition lfd, FormStatus fs, String uri) throws Exception {
		// TODO Auto-generated method stub
		String formId = lfd.getSubmissionKey(uri);
		
    	String baseUrl = serverInfo.getUrl() + "/view/downloadSubmission";
    	
        HttpContext localContext = serverInfo.getHttpContext();

        HttpClient httpclient = serverInfo.getHttpClient();

        Map<String,String> params = new HashMap<String,String>();
        params.put("formId", formId);
        String fullUrl = WebUtils.createLinkWithProperties(baseUrl, params);
        DocumentFetchResult result = getXmlDocument(fullUrl, localContext,
                httpclient, "Fetch of a submission failed.  Detailed error: ", 
                			"Fetch of a submission failed.");

        if (result.errorMessage != null) {
            throw new SubmissionDownloadException(result.errorMessage);
        }

        // and parse the document...
        List<Attachment> attachmentList = new ArrayList<Attachment>();
        Element rootSubmissionElement = null;
        String instanceID = null;
        
        // Attempt parsing
        Element submissionElement = result.doc.getRootElement();
        if (!submissionElement.getName().equals("submission")) {
        	String msg = "Parsing downloadSubmission reply -- root element is not <submission> :"
                + submissionElement.getName();
        	logger.error(msg);
        	throw new SubmissionDownloadException(msg);
        }
        String namespace = submissionElement.getNamespace();
        if (!namespace.equalsIgnoreCase(NAMESPACE_OPENDATAKIT_ORG_SUBMISSIONS) ) {
        	String msg = "Parsing downloadSubmission reply -- root element namespace is incorrect:"
                    + namespace;
        	logger.error(msg);
        	throw new SubmissionDownloadException(msg);
        }
        int nElements = submissionElement.getChildCount();
        for (int i = 0; i < nElements; ++i) {
            if (submissionElement.getType(i) != Element.ELEMENT) {
                // e.g., whitespace (text)
                continue;
            }
            Element subElement = (Element) submissionElement.getElement(i);
            namespace = subElement.getNamespace();
            if (!namespace.equalsIgnoreCase(NAMESPACE_OPENDATAKIT_ORG_SUBMISSIONS) ) {
                // someone else's extension?
                continue;
            }
            String name = subElement.getName();
            if ( name.equalsIgnoreCase("data")) {
            	// find the root submission element and get its instanceID attribute
            	int nIdElements = subElement.getChildCount();
	            for (int j = 0; j < nIdElements; ++j) {
	                if (subElement.getType(j) != Element.ELEMENT) {
	                    // e.g., whitespace (text)
	                    continue;
	                }
	                rootSubmissionElement = (Element) subElement.getElement(j);
	                break;
	            }
	            if ( rootSubmissionElement == null ) {
	            	throw new SubmissionDownloadException("no submission body found in submissionDownload response");
	            }

	            instanceID = rootSubmissionElement.getAttributeValue(null, "instanceID");
            	if ( instanceID == null ) {
            		throw new SubmissionDownloadException("instanceID attribute value is null");
            	}
            } else if ( name.equalsIgnoreCase("mediaFile")) {
	            int nIdElements = subElement.getChildCount();
	            String filename = null;
	            String hash = null;
	            String downloadUrl = null;
	            for (int j = 0; j < nIdElements; ++j) {
	                if (subElement.getType(j) != Element.ELEMENT) {
	                    // e.g., whitespace (text)
	                    continue;
	                }
	                Element mediaSubElement = (Element) subElement.getElement(j);
	                name = mediaSubElement.getName();
	                if ( name.equalsIgnoreCase("filename")) {
	                	filename = XFormParser.getXMLText(mediaSubElement, true);
	                } else if ( name.equalsIgnoreCase("hash")) {
	                	hash = XFormParser.getXMLText(mediaSubElement, true);
	                } else if ( name.equalsIgnoreCase("downloadUrl")) {
	                	downloadUrl = XFormParser.getXMLText(mediaSubElement, true);
	                }
	            }
	            attachmentList.add(new Attachment(filename, hash, downloadUrl));
            } else {
            	logger.warn("Unrecognized tag inside submission: " + name);
            }
        }
        
        if ( rootSubmissionElement == null ) {
        	throw new SubmissionDownloadException("No submission body found");
        }
    	if ( instanceID == null ) {
    		throw new SubmissionDownloadException("instanceID attribute value is null");
    	}
        String msg = "Fetched instanceID=" + instanceID;
        logger.error(msg);

        // create instance directory...
        String instanceDirName = asFilesystemSafeName(instanceID);
        File instanceDir = new File(formInstancesDir, instanceDirName);
        if ( !instanceDir.mkdir() ) {
        	throw new SubmissionDownloadException("unable to create instance dir");
        }
        
        // fetch attachments
        for ( Attachment a : attachmentList ) {
        	commonDownloadFile(new File(instanceDir, a.filename), a.downloadUrl);
        }
        // write submission file
        File submissionFile = new File(instanceDir, "submission.xml");
        FileWriter fo = new FileWriter(submissionFile);
    	KXmlSerializer serializer = new KXmlSerializer();

        serializer.setOutput(fo);
    	// setting the response content type emits the xml header.
    	// just write the body here...
        rootSubmissionElement.setPrefix(null, NAMESPACE_OPENDATAKIT_ORG_SUBMISSIONS);
        rootSubmissionElement.write(serializer);
        serializer.flush();
        serializer.endDocument();
        fo.close();
	}


	/**
     * Common method for returning a parsed xml document given a url and the http context and client
     * objects involved in the web connection.
     * 
     * @param urlString
     * @param localContext
     * @param httpclient
     * @return
     */
	private DocumentFetchResult getXmlDocument(String urlString, HttpContext localContext,
            HttpClient httpclient, String fetch_doc_failed, String fetch_doc_failed_no_detail) {

        URI u = null;
        try {
            URL url = new URL(urlString);
            u = url.toURI();
        } catch (Exception e) {
            e.printStackTrace();
            return new DocumentFetchResult(e.getLocalizedMessage()
                    + " while accessing: " + urlString);
        }

        // set up request...
        HttpGet req = WebUtils.createOpenRosaHttpGet(u);

        HttpResponse response = null;
        try {
            response = httpclient.execute(req, localContext);
            int statusCode = response.getStatusLine().getStatusCode();

            HttpEntity entity = response.getEntity();
            String lcContentType = (entity == null) ? null : entity.getContentType().getValue().toLowerCase();

            if (entity != null
                    && (statusCode != 200 || 
                    		!(lcContentType.contains(HTTP_CONTENT_TYPE_TEXT_XML) ||
                    		  lcContentType.contains(HTTP_CONTENT_TYPE_APPLICATION_XML)))) {
                try {
                    // don't really care about the stream...
                    InputStream is = response.getEntity().getContent();
                    // read to end of stream...
                    final long count = 1024L;
                    while (is.skip(count) == count)
                        ;
                    is.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            if (statusCode != 200) {
                String webError =
                    response.getStatusLine().getReasonPhrase() + " (" + statusCode + ")";

                return new DocumentFetchResult(fetch_doc_failed_no_detail + webError
                        + " while accessing: " + u.toString()
                        + " A network login screen may be interfering with the transmission to the server.");
            }

            if (entity == null) {
            	logger.error("No entity body returned from: " + u.toString() + " is not text/xml");
                return new DocumentFetchResult(fetch_doc_failed
                        + " while accessing: " + u.toString()
                        + " A network login screen may be interfering with the transmission to the server.");
            }

            if (!entity.getContentType().getValue().toLowerCase().contains(
                HTTP_CONTENT_TYPE_TEXT_XML)) {
            	logger.error("ContentType: " + entity.getContentType().getValue() + "returned from: "
                        + u.toString() + " is not text/xml");
                return new DocumentFetchResult(fetch_doc_failed
                        + " while accessing: " + u.toString()
                        + " A network login screen may be interfering with the transmission to the server.");
            }

            // parse response
            Document doc = null;
            try {
                InputStream is = null;
                InputStreamReader isr = null;
                try {
                    is = entity.getContent();
                    isr = new InputStreamReader(is, "UTF-8");
                    doc = new Document();
                    KXmlParser parser = new KXmlParser();
                    parser.setInput(isr);
                    parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
                    doc.parse(parser);
                    isr.close();
                } finally {
                    if (isr != null) {
                        try {
                            isr.close();
                        } catch (Exception e) {
                            // no-op
                        }
                    }
                    if (is != null) {
                        try {
                            is.close();
                        } catch (Exception e) {
                            // no-op
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                logger.error("Parsing failed with " + e.getMessage());
                return new DocumentFetchResult(fetch_doc_failed
                        + " while accessing: " + u.toString());
            }

            boolean isOR = false;
            Header[] fields = response.getHeaders(WebUtils.OPEN_ROSA_VERSION_HEADER);
            if (fields != null && fields.length >= 1) {
                isOR = true;
                boolean versionMatch = false;
                boolean first = true;
                StringBuilder b = new StringBuilder();
                for (Header h : fields) {
                    if (WebUtils.OPEN_ROSA_VERSION.equals(h.getValue())) {
                        versionMatch = true;
                        break;
                    }
                    if (!first) {
                        b.append("; ");
                    }
                    first = false;
                    b.append(h.getValue());
                }
                if (!versionMatch) {
                    logger.warn(WebUtils.OPEN_ROSA_VERSION_HEADER + " unrecognized version(s): "
                            + b.toString());
                }
            }
            return new DocumentFetchResult(doc, isOR);
        } catch (Exception e) {
            e.printStackTrace();
            return new DocumentFetchResult(
            		fetch_doc_failed_no_detail
                    + e.getLocalizedMessage() + " while accessing: "
                    + u.toString());
        }
    }


    private static class MediaFile {
        final String filename;
        final String hash;
        final String downloadUrl;


        MediaFile(String filename, String hash, String downloadUrl) {
            this.filename = filename;
            this.hash = hash;
            this.downloadUrl = downloadUrl;
        }
    }


    private String downloadManifestAndMediaFiles(File mediaDir, FormStatus fs) {
    	RemoteFormDefinition fd = (RemoteFormDefinition) fs.getFormDefinition();
        if (fd.getManifestUrl() == null)
            return null;
    	fs.setStatusString("Fetching form manifest");
        EventBus.publish(new FormStatusEvent(fs));

        List<MediaFile> files = new ArrayList<MediaFile>();
        // get shared HttpContext so that authentication and cookies are retained.
        HttpContext localContext = serverInfo.getHttpContext();

        HttpClient httpclient = serverInfo.getHttpClient();

        DocumentFetchResult result =
            getXmlDocument(fd.getManifestUrl(), localContext, httpclient,
                "Fetch of manifest failed. Detailed reason: ",
                "Fetch of manifest failed ");

        if (result.errorMessage != null) {
            return result.errorMessage;
        }

        String errMessage = "Fetch of manifest failed while accessing: "
        						+ fd.getManifestUrl();

        if (!result.isOpenRosaResponse) {
            logger.error("Manifest reply doesn't report an OpenRosa version -- bad server?");
            return errMessage;
        }

        // Attempt OpenRosa 1.0 parsing
        Element manifestElement = result.doc.getRootElement();
        if (!manifestElement.getName().equals("manifest")) {
        	logger.error("Root element is not <manifest> -- was " + manifestElement.getName());
            return errMessage;
        }
        String namespace = manifestElement.getNamespace();
        if (!isXformsManifestNamespacedElement(manifestElement)) {
        	logger.error("Root element Namespace is incorrect: " + namespace);
            return errMessage;
        }
        int nElements = manifestElement.getChildCount();
        for (int i = 0; i < nElements; ++i) {
            if (manifestElement.getType(i) != Element.ELEMENT) {
                // e.g., whitespace (text)
                continue;
            }
            Element mediaFileElement = (Element) manifestElement.getElement(i);
            if (!isXformsManifestNamespacedElement(mediaFileElement)) {
                // someone else's extension?
                continue;
            }
            String name = mediaFileElement.getName();
            if (name.equalsIgnoreCase("mediaFile")) {
                String filename = null;
                String hash = null;
                String downloadUrl = null;
                // don't process descriptionUrl
                int childCount = mediaFileElement.getChildCount();
                for (int j = 0; j < childCount; ++j) {
                    if (mediaFileElement.getType(j) != Element.ELEMENT) {
                        // e.g., whitespace (text)
                        continue;
                    }
                    Element child = mediaFileElement.getElement(j);
                    if (!isXformsManifestNamespacedElement(child)) {
                        // someone else's extension?
                        continue;
                    }
                    String tag = child.getName();
                    if (tag.equals("filename")) {
                        filename = XFormParser.getXMLText(child, true);
                        if (filename != null && filename.length() == 0) {
                            filename = null;
                        }
                    } else if (tag.equals("hash")) {
                        hash = XFormParser.getXMLText(child, true);
                        if (hash != null && hash.length() == 0) {
                            hash = null;
                        }
                    } else if (tag.equals("downloadUrl")) {
                        downloadUrl = XFormParser.getXMLText(child, true);
                        if (downloadUrl != null && downloadUrl.length() == 0) {
                            downloadUrl = null;
                        }
                    }
                }
                if (filename == null || downloadUrl == null || hash == null) {
                	logger.error("Manifest entry " + Integer.toString(i)
                            + " is missing one or more tags: filename, hash, or downloadUrl");
                    return errMessage;
                }
                files.add(new MediaFile(filename, hash, downloadUrl));
            }
        }
        // OK we now have the full set of files to download...
        logger.info("Downloading " + files.size() + " media files.");
        int mCount = 0;
        if (files.size() > 0) {
            for (MediaFile m : files) {
                ++mCount;
            	fs.setStatusString(String.format(" (getting %1$d of %2$d media files)", mCount, files.size()));
                EventBus.publish(new FormStatusEvent(fs));
                try {
                    downloadMediaFileIfChanged(mediaDir, m);
                } catch (Exception e) {
                    return e.getLocalizedMessage();
                }
            }
        }
        return null;
    }


    /**
     * Common routine to download a document from the downloadUrl and save the contents in the file
     * 'f'. Shared by media file download and form file download.
     * 
     * @param f
     * @param downloadUrl
     * @throws Exception
     */
    private void commonDownloadFile(File f, String downloadUrl) throws Exception {

        // OK. We need to download it because we either:
        // (1) don't have it
        // (2) don't know if it is changed because the hash is not md5
        // (3) know it is changed
        URI u = null;
        try {
            URL uurl = new URL(downloadUrl);
            u = uurl.toURI();
        } catch (MalformedURLException e) {
            e.printStackTrace();
            throw e;
        } catch (URISyntaxException e) {
            e.printStackTrace();
            throw e;
        }

        // get shared HttpContext so that authentication and cookies are retained.
        HttpContext localContext = serverInfo.getHttpContext();

        HttpClient httpclient = serverInfo.getHttpClient();

        // set up request...
        HttpGet req = WebUtils.createOpenRosaHttpGet(u);

        HttpResponse response = null;
        try {
            response = httpclient.execute(req, localContext);
            int statusCode = response.getStatusLine().getStatusCode();

            if (statusCode != 200) {
                String errMsg = String.format(FETCH_FAILED_DETAILED_REASON,
                			f.getAbsolutePath())
                            + response.getStatusLine().getReasonPhrase() +
                            " (" + statusCode + ")";
                logger.error(errMsg);
                throw new DownloadException(errMsg);
            }

            // write connection to file
            InputStream is = null;
            OutputStream os = null;
            try {
                is = response.getEntity().getContent();
                os = new FileOutputStream(f);
                byte buf[] = new byte[1024];
                int len;
                while ((len = is.read(buf)) > 0) {
                    os.write(buf, 0, len);
                }
                os.flush();
            } finally {
                if (os != null) {
                    try {
                        os.close();
                    } catch (Exception e) {
                    }
                }
                if (is != null) {
                    try {
                        is.close();
                    } catch (Exception e) {
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }


    private void downloadMediaFileIfChanged(File mediaDir, MediaFile m) throws Exception {

        File mediaFile = new File(mediaDir, m.filename);

        if (m.hash.startsWith(MD5_COLON_PREFIX)) {
            // see if the file exists and has the same hash
            String hashToMatch = m.hash.substring(MD5_COLON_PREFIX.length());
            if (mediaFile.exists()) {
                String hash = CommonUtils.getMd5Hash(mediaFile);
                if (hash.equalsIgnoreCase(hashToMatch))
                    return;
                mediaFile.delete();
            }
        }

        commonDownloadFile(mediaFile, m.downloadUrl);
    }

    private String asFilesystemSafeName(String formName ) {
        String rootName = formName.replaceAll("[^\\p{L}\\p{Digit}]", " ");
        rootName = rootName.replaceAll("\\p{javaWhitespace}+", " ");
        rootName = rootName.trim();
        return rootName;
    }
    
    private File getFormDirectory( File briefcaseDir, String formName ) throws DownloadException {
        // clean up friendly form name...
        String rootName = asFilesystemSafeName(formName);
        File scratch = FormFileUtils.getScratchFormsPath(briefcaseDir);
        File formPath = new File(scratch, rootName);
        if ( !formPath.exists() && !formPath.mkdirs() ) {
        	throw new DownloadException("unable to create directory: " + formPath.getAbsolutePath());
        }
    	return formPath;
    }
    
    private File getFormDefinitionFile( File briefcaseDir, String formName ) throws DownloadException {
        String rootName = asFilesystemSafeName(formName);
        File formPath = getFormDirectory(briefcaseDir, formName);
        File formDefnFile = new File(formPath, rootName + ".xml");
        
        return formDefnFile;
    }
    
    private File getMediaDirectory( File briefcaseDir, String formName ) throws DownloadException {
        String rootName = asFilesystemSafeName(formName);
        File formPath = getFormDirectory(briefcaseDir, formName);
        File mediaDir = new File(formPath, rootName + "-media");
        if ( !mediaDir.exists() && !mediaDir.mkdirs() ) {
        	throw new DownloadException("unable to create directory: " + mediaDir.getAbsolutePath());
        }

    	return mediaDir;
    }

    private File getFormInstancesDirectory(File briefcaseDir, String formName) throws DownloadException {
        File formPath = getFormDirectory(briefcaseDir, formName);
        File instancesDir = new File(formPath, "instances");
        if ( !instancesDir.exists() && !instancesDir.mkdirs() ) {
        	throw new DownloadException("unable to create directory: " + instancesDir.getAbsolutePath());
        }

        return instancesDir;
	}

}
