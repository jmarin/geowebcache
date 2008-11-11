/**
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * @author Arne Kepp, Marius Suta,  The Open Planning Project, Copyright 2008
 */
package org.geowebcache.util;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.geowebcache.GeoWebCacheException;
import org.geowebcache.cache.CacheException;
import org.geowebcache.cache.CacheFactory;
import org.geowebcache.cache.file.FileCache;
import org.geowebcache.cache.file.FilePathKey2;
import org.geowebcache.layer.Grid;
import org.geowebcache.layer.TileLayer;
import org.geowebcache.layer.wms.WMSLayer;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.context.WebApplicationContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomReader;
import com.thoughtworks.xstream.io.xml.DomWriter;

public class XMLConfiguration implements Configuration, ApplicationContextAware {
    private static Log log = LogFactory.getLog(org.geowebcache.util.XMLConfiguration.class);

    private static final String CONFIGURATION_FILE_NAME = "geowebcache.xml";
    
    private static final String[] CONFIGURATION_REL_PATHS = 
        { "/WEB-INF/classes", "/../resources" };
    
    private WebApplicationContext context;

    private CacheFactory cacheFactory = null;

    private String absPath = null;

    private String relPath = null;

    private File configH = null;

    private FileCache fileCache = null;
    
    /**
     * XMLConfiguration class responsible for reading/writing layer
     * configurations to and from XML file
     * 
     * @param cacheFactory
     */
    public XMLConfiguration(CacheFactory cacheFactory) {
        this.cacheFactory = cacheFactory;
    }

    public XMLConfiguration() {
    }
    
    private File findConfFile() throws GeoWebCacheException {
        if (configH == null) {
            determineConfigDirH();
        }

        File xmlFile = null;
        if (configH != null) {
            // Find the property file
            xmlFile = new File(configH.getAbsolutePath() + File.separator + CONFIGURATION_FILE_NAME);
        } else {
            throw new GeoWebCacheException("Unable to determine configuration directory.");
        }

        if (xmlFile != null) {
            log.trace("Found configuration file in "+ configH.getAbsolutePath());
        } else {
            throw new GeoWebCacheException("Found no configuration file in "+ configH.getAbsolutePath());
        }
        
        return xmlFile;
    }

    /**
     * Method responsible for loading XML configuration file
     * 
     */
    public Map<String, TileLayer> getTileLayers() throws GeoWebCacheException {
        File xmlFile = findConfFile();
        
        HashMap<String, TileLayer> layers = new HashMap<String, TileLayer>();

        // load configurations into Document
        Document docc = loadIntoDocument(xmlFile);
        Element root = docc.getDocumentElement();
        NodeList allLayerNodes = root.getChildNodes();

        XStream xs = getConfiguredXStream(new XStream());

        TileLayer result = null;
        for (int i = 0; i < allLayerNodes.getLength(); i++) {
            if (allLayerNodes.item(i) instanceof Element) {
                Element e = (Element) allLayerNodes.item(i);
                if (e.getTagName().equalsIgnoreCase("wmslayer"))
                    result = (WMSLayer) xs.unmarshal(new DomReader(
                            (Element) allLayerNodes.item(i)));
                result.setCacheFactory(this.cacheFactory);
                layers.put(result.getName(), result);
            }
        }

        return layers;
    }

    public static XStream getConfiguredXStream(XStream xstream) {
        XStream xs = xstream;
        xs.setMode(XStream.NO_REFERENCES);
        
        xs.alias("layer", TileLayer.class);
        xs.alias("wmslayer", WMSLayer.class);
        //xs.aliasField("layer-name", TileLayer.class, "name");
        xs.alias("grids", new ArrayList<Grid>().getClass());
        xs.alias("grid", Grid.class);
        xs.aliasType("format", String.class);
        xs.alias("mimeFormats", new ArrayList<String>().getClass());
        xs.aliasType("WMSurl", String.class);
        xs.aliasType("errormime", String.class);
        xs.alias("metaWidthHeight", new int[1].getClass());
        //xs.alias("width", Integer.class);
        //xs.alias("height", Integer.class);
        xs.aliasType("version", String.class);
        xs.alias("tiled", boolean.class);
        xs.alias("transparent", boolean.class);
        xs.alias("SRS", org.geowebcache.layer.SRS.class);
        
        xs.alias("zoomStart", int.class);
        xs.alias("zoomStop", int.class);
        //xs.alias("debugheaders", boolean.class);
        
        return xs;
    }

    /**
     * Method responsible for writing out TileLayer objects
     * 
     * @param tl
     *            a new TileLayer object to be serialized to XML
     * @return true if operation succeeded, false otherwise
     */

    public boolean createLayer(TileLayer tl) throws GeoWebCacheException {
        File xmlFile = findConfFile();

        // load configurations into Document
        Document docc = loadIntoDocument(xmlFile);
        Element root = docc.getDocumentElement();

        // create the XStream for serializing tileLayers to XML
        XStream xs = getConfiguredXStream(new XStream());
        // sent to XML
        xs.marshal(tl, new DomWriter((Element) root));

        try {
            DOMSource source = new DOMSource(docc);
            StreamResult result = new StreamResult(xmlFile);

            // write the DOM to the file
            Transformer xformer = 
                TransformerFactory.newInstance().newTransformer();
            xformer.transform(source, result);
        } catch (TransformerConfigurationException e) {
            log.error(e.getMessage());
        } catch (TransformerException e) {
            log.error(e.getMessage());
        }

        return true;

    }

    /**
     * Method responsible for modifying an existing layer.
     * 
     * @param currentLayer
     *            the name of the layer to be modified
     * @param tl
     *            the new layer to overwrite the existing layer
     * @return true if operation succeeded, false otherwise
     */

    public boolean modifyLayer(String currentLayer, TileLayer tl) throws GeoWebCacheException {

        return deleteLayer(currentLayer) && createLayer(tl);

    }

    /**
     * Method responsible for deleting existing layers
     * 
     * @param layerName the name of the layer to be deleted
     * @return true if operation succeeded, false otherwise
     */
    public boolean deleteLayer(String layerName) {
        if (configH == null) {
            determineConfigDirH();
        }

        File xmlFile = null;
        if (configH == null) {
            log.error("deleteLayer() - Missing XML configuration file?");
            return false;
        } else {
            // Find the configuration file
            xmlFile = new File(configH.getAbsolutePath() + File.separator + "geowebcache.xml");
        }

        if (xmlFile != null) {
            log.trace("Found configuration file in "+ configH.getAbsolutePath());
        } else {
            log.error("Found no configuration file in "+ configH.getAbsolutePath());
            return false;
        }

        // load configurations into Document
        Document docc = loadIntoDocument(xmlFile);
        Element root = docc.getDocumentElement();

        NodeList nl = docc.getElementsByTagName("layer-name");

        if (nl.getLength() == 0)
            return false;
        else {
            Element toDelete = null;
            for (int i = 0; i < nl.getLength(); i++) {
                Node tmp = (Node) nl.item(i).getFirstChild();
                if (tmp.getNodeValue().equals(layerName)) {
                    toDelete = (Element) nl.item(i);
                    break;
                } else {
                    continue;
                }
            }

            if (toDelete != null) {
                root.removeChild(toDelete.getParentNode());

                try {
                    DOMSource source = new DOMSource(docc);
                    StreamResult result = new StreamResult(xmlFile);

                    // write the DOM to the file
                    Transformer xformer = TransformerFactory.newInstance().newTransformer();
                    xformer.transform(source, result);
                } catch (TransformerConfigurationException e) {
                    log.error("XMLConfiguration encountered "+e.getMessage());
                } catch (TransformerException e) {
                    log.error("XMLConfiguration encountered "+e.getMessage());
                }
                return true;
            }
            
            return false;
        }
    }

    /**
     * Method responsible for loading xml configuration file and parsing it into
     * a W3C DOM Document
     * 
     * @param file
     *            the file contaning the layer configurations
     * @return W3C DOM Document
     */
    private org.w3c.dom.Document loadIntoDocument(File file) {
        org.w3c.dom.Document document = null;
        try {
            DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
            docBuilderFactory.setNamespaceAware(true);
            DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
            document = docBuilder.parse(file);
        } catch (ParserConfigurationException pce) {
            log.error(pce.getMessage());
            pce.printStackTrace();
        } catch (IOException ei) {
            log.error("Exception occured while creating documet from file " + file.getAbsolutePath());
        } catch (SAXException saxe) {
            log.error(saxe.getMessage());
        }
        return document;
    }

    public void determineConfigDirH() {
        String baseDir = context.getServletContext().getRealPath("");
        
        /*
         * Try 
         * 1) absolute path (specified in bean defn)
         * 2) relative path (specified in bean defn)
         * 3) environment variables
         * 4) standard paths
         */
        if (absPath != null) {
            configH = new File(absPath);
        } else if (relPath != null) {
            configH = new File(baseDir + relPath);
            log.info("Configuration directory set to: "
                    + configH.getAbsolutePath());
        } else if (relPath == null) {
            // Try env variables
            File tmpPath = null;

            if (fileCache != null) {
                try {
                    // Careful, this appends a separator
                    tmpPath = new File(fileCache.getDefaultPrefix(CONFIGURATION_FILE_NAME));

                    if (tmpPath.exists() && tmpPath.canRead()) {
                        String filePath = tmpPath.getAbsolutePath();
                        configH = new File(filePath.substring(0, 
                                filePath.length()
                                - CONFIGURATION_FILE_NAME.length() - 1));
                    }
                } catch (CacheException ce) {
                    // Ignore
                }
            }

            // Finally, try "standard" paths if we have to.
            if (configH == null) {
                for (int i = 0; i < CONFIGURATION_REL_PATHS.length; i++) {
                    relPath = CONFIGURATION_REL_PATHS[i];
                    if (File.separator.equals("\\")) {
                        relPath = relPath.replace("/", "\\");
                    }

                    tmpPath = new File(baseDir + relPath + File.separator
                            + CONFIGURATION_FILE_NAME);

                    if (tmpPath.exists() && tmpPath.canRead()) {
                        log.info("No configuration directory was specified, using "
                                        + tmpPath.getAbsolutePath());
                        configH = new File(baseDir + relPath);
                    }
                }
            }
        }
       
        if(configH == null) {
            log.error("Failed to find geowebcache.xml");
        } else {
            log.info("Configuration directory set to: "+ configH.getAbsolutePath());
        
            if (!configH.exists() || !configH.canRead()) {
                log.error(configH.getAbsoluteFile()+ " cannot be read or does not exist!");
            }
        }
    }

    public String getIdentifier() {
        return configH.getAbsolutePath();
    }

    public void setRelativePath(String relPath) {
        this.relPath = relPath;
    }

    public void setAbsolutePath(String absPath) {
        this.absPath = absPath;
    }
    
    public void setFileCache(FileCache fileCache) {
        this.fileCache = fileCache;
    }

    public void setApplicationContext(ApplicationContext arg0)
            throws BeansException {
        context = (WebApplicationContext) arg0;
    }

    public CacheFactory getCacheFactory() {
        return this.cacheFactory;
    }

}
