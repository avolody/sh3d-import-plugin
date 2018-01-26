package uhh.swa.importer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPathException;

import org.apache.commons.io.FileUtils;
import org.javatuples.Pair;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import com.eteks.sweethome3d.plugin.PluginAction;

import it.svario.xpathapi.jaxp.XPathAPI;

public class ImportAction extends PluginAction {

    private Transformer transformer;

    public ImportAction() {
        putPropertyValue(Property.MENU, "Project");
        putPropertyValue(Property.NAME, "Import Projects");
        setEnabled(true);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(null, message, "Error", JOptionPane.ERROR_MESSAGE);
    }

    @Override
    public void execute() {
        ImportFilter filter = new ImportFilter();
        JFileChooser chooser = new JFileChooser();
        //noinspection SpellCheckingInspection
        chooser.setFileFilter(filter);
        int option = chooser.showOpenDialog(null);
        if (option == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (!filter.accept(file))
                showError("Invalid file selected.");
            else {
                String dirName = file.getAbsolutePath();
                dirName = dirName.substring(0, dirName.lastIndexOf('.'));
                File dir = new File(dirName);
                if (!dir.isDirectory())
                    dir = null;
                List<File> files = splitFile(file, dir);
//                files.forEach(f -> importFile(f));
            }
        }
    }

    private List<File> splitException(String message, Exception e) {
        e.printStackTrace();
        showError(message);
        return new ArrayList<>();
    }

    private List<File> splitFile(File file, File directory) {
        Path temp;
        List<File> files = new ArrayList<>();
        try {
            temp = Files.createTempDirectory("furniture");
        } catch (IOException e) {
            return splitException("Unable to create temporary directory.", e);
        }
        if (directory!=null) {
            try {
                FileUtils.copyDirectoryToDirectory(directory, temp.toFile());
            } catch (IOException e) {
                return splitException("Unable to copy related files.", e);
            }
        }
        DocumentBuilder builder;
        try {
            builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
        Document source;
        try {
            source = builder.parse(file);

            List<Document> documents;
            if (XPathAPI.selectSingleNode(source, "//node[@name='SketchUp']") != null)
                documents = sketchUpImport(builder, source);
            else documents = freeCADImport(builder, source);

            TransformerFactory tFactory = TransformerFactory.newInstance();
            transformer = tFactory.newTransformer();

            for (int i = 0, documentsSize = documents.size(); i < documentsSize; i++) {
                Document document = documents.get(i);
                File newFile = new File(temp.toFile(), i + ".dae");
                writeDocumentToFile(document, newFile);
                files.add(newFile);
            }

        } catch (XPathException | SAXException | IOException e) {
            return splitException("Invalid file contents.", e);
        } catch (TransformerException e) {
            return splitException("Failed to write temporary file.", e);
        }
        return files;
    }

    private List<Document> sketchUpImport(DocumentBuilder builder, Document source) throws XPathException {
        List<Pair<String, String>> relatedNodes = new ArrayList<>();
        List<Pair<String, String>> relatedSceneNodes = new ArrayList<>();
        List<Set<String>> consolidatedRelatedNodes = new ArrayList<>();
        List<Document> documents = new ArrayList<>();

        String sceneID = XPathAPI.selectSingleNode(source, "/COLLADA/scene/instance_visual_scene").getAttributes().getNamedItem("url").getNodeValue();
        sceneID = sceneID.substring(1, sceneID.length());

        List<String> floatingSceneNodes = XPathAPI.selectListOfNodes(source, "/COLLADA/library_visual_scenes/visual_scene/node[@name='SketchUp']/instance_geometry")
                .parallelStream()
                .map(node -> node.getAttributes()
                        .getNamedItem("url")
                        .getNodeValue())
                .map(s -> s.substring(1, s.length()))
                .collect(Collectors.toList());

        List<String> nodes = XPathAPI.selectListOfNodes(source, "/COLLADA/library_nodes/node")
                .parallelStream()
                .map(node -> node.getAttributes()
                        .getNamedItem("id")
                        .getNodeValue())
                .collect(Collectors.toList());

        for (String node : nodes) {
            XPathAPI.selectListOfNodes(source, "/COLLADA/library_visual_scenes/visual_scene/node[@name='SketchUp']/node[descendant::instance_node[@url='#" + node + "']]")
                    .parallelStream()
                    .map(n -> n.getAttributes()
                            .getNamedItem("id")
                            .getNodeValue())
                    .forEach(id -> relatedSceneNodes.add(new Pair<>(node, id)));
        }

        for (String node : nodes) {
            XPathAPI.selectListOfNodes(source, "/COLLADA/library_nodes/node[descendant::instance_node[@url='#" + node + "']]")
                    .parallelStream()
                    .map(n -> n.getAttributes()
                            .getNamedItem("id")
                            .getNodeValue())
                    .distinct()
                    .forEach(s -> relatedNodes.add(new Pair<>(node, s)));
        }

        consolidateNodes(relatedNodes, consolidatedRelatedNodes, nodes);

        for (Set<String> libraryNodes : consolidatedRelatedNodes) {
            Document temp = builder.newDocument();
            Node root = createSkeleton(source, temp);

            List<String> geometryIDs = new ArrayList<>();
            Element library_nodes = temp.createElement("library_nodes");
            for (String nodeID : libraryNodes) {
                XPathAPI.selectListOfNodes(source, "/COLLADA/library_nodes/node[@id='" + nodeID + "']//instance_geometry")
                        .parallelStream()
                        .map(n -> n.getAttributes()
                                .getNamedItem("url")
                                .getNodeValue())
                        .map(s -> s.substring(1, s.length()))
                        .forEach(geometryIDs::add);
                library_nodes.appendChild(temp.importNode(XPathAPI.selectSingleNode(source, "/COLLADA/library_nodes/node[@id='" + nodeID + "']"), true));
            }
            root.appendChild(library_nodes);

            Element geometries = temp.createElement("library_geometries");
            for (String geometryID : geometryIDs) {
                geometries.appendChild(temp.importNode(XPathAPI.selectSingleNode(source, "/COLLADA/library_geometries/geometry[@id='" + geometryID + "']"), true));
            }
            root.appendChild(geometries);

            List<String> sceneNodes = relatedSceneNodes.parallelStream()
                    .filter(sn -> libraryNodes.contains(sn.getValue0()))
                    .map(Pair::getValue1)
                    .collect(Collectors.toList());
            if (!sceneNodes.isEmpty()) {
                Element visualScenes = temp.createElement("library_visual_scenes");
                Element visualScene = temp.createElement("visual_scene");
                Element sketchUpNode = temp.createElement("node");
                sketchUpNode.setAttribute("name", "SketchUp");
                visualScene.setAttribute("id", sceneID);
                for (String nodeID : sceneNodes) {
                    sketchUpNode.appendChild(temp.importNode(XPathAPI.selectSingleNode(source, "/COLLADA/library_visual_scenes/visual_scene/node/node[@id='" + nodeID + "']"), true));
                }
                visualScene.appendChild(sketchUpNode);
                visualScenes.appendChild(visualScene);
                root.appendChild(visualScenes);
            }

            temp.appendChild(root);
            documents.add(temp);
        }


        if (!floatingSceneNodes.isEmpty()) {
            Document temp = builder.newDocument();
            Node root = createSkeleton(source, temp);

            Element geometries = temp.createElement("library_geometries");
            for (String geometryID : floatingSceneNodes) {
                geometries.appendChild(temp.importNode(XPathAPI.selectSingleNode(source, "/COLLADA/library_geometries/geometry[@id='" + geometryID + "']"), true));
            }
            root.appendChild(geometries);

            Element visualScenes = temp.createElement("library_visual_scenes");
            Element visualScene = temp.createElement("visual_scene");
            Element sketchUpNode = temp.createElement("node");
            sketchUpNode.setAttribute("name", "SketchUp");
            visualScene.setAttribute("id", sceneID);
            for (String geometryID : floatingSceneNodes) {
                sketchUpNode.appendChild(temp.importNode(XPathAPI.selectSingleNode(source, "/COLLADA/library_visual_scenes/visual_scene/node/instance_geometry[@url='#" + geometryID + "']"), true));
            }
            visualScene.appendChild(sketchUpNode);
            visualScenes.appendChild(visualScene);
            root.appendChild(visualScenes);

            temp.appendChild(root);
            documents.add(temp);
        }

        return documents;
    }

    private Node createSkeleton(Document source, Document temp) throws XPathException {
        Node root = XPathAPI.selectSingleNode(source, "/COLLADA").cloneNode(false);
        root.appendChild(XPathAPI.selectSingleNode(source, "/COLLADA/asset").cloneNode(true));
        root.appendChild(XPathAPI.selectSingleNode(source, "/COLLADA/scene").cloneNode(true));
        if (XPathAPI.selectSingleNode(source, "/COLLADA/library_effects") != null)
            root.appendChild(XPathAPI.selectSingleNode(source, "/COLLADA/library_effects").cloneNode(true));
        if (XPathAPI.selectSingleNode(source, "/COLLADA/library_materials") != null)
            root.appendChild(XPathAPI.selectSingleNode(source, "/COLLADA/library_materials").cloneNode(true));
        if (XPathAPI.selectSingleNode(source, "/COLLADA/library_images") != null)
            root.appendChild(XPathAPI.selectSingleNode(source, "/COLLADA/library_images").cloneNode(true));
        root = temp.importNode(root, true);
        return root;
    }

    private void consolidateNodes(List<Pair<String, String>> relatedNodes, List<Set<String>> consolidatedRelatedNodes, List<String> nodes) {
        while (!relatedNodes.isEmpty())
            consolidatedRelatedNodes.add(removeRelatedNodes(relatedNodes, new LinkedBlockingQueue<>(Collections.singletonList(relatedNodes.get(0).getValue0()))));

        nodes.forEach(node -> {
            if (consolidatedRelatedNodes.stream().noneMatch(rel -> rel.contains(node)))
                consolidatedRelatedNodes.add(new HashSet<>(Collections.singleton(node)));
        });
    }

    private Set<String> removeRelatedNodes(List<Pair<String, String>> relatedPairs, Queue<String> queue) {
        Set<String> set = new HashSet<>();
        while (!queue.isEmpty()) {
            Iterator<Pair<String, String>> iterator = relatedPairs.iterator();
            String toRemove = queue.remove();
            set.add(toRemove);
            while (iterator.hasNext()) {
                Pair<String, String> pair = iterator.next();
                if (pair.getValue0().equals(toRemove)) {
                    iterator.remove();
                    if (!queue.contains(pair.getValue1()))
                        queue.add(pair.getValue1());
                }
            }
        }
        return set;
    }

    private List<Document> freeCADImport(DocumentBuilder builder, Document source) throws XPathException {
        List<Pair<String, String>> relatedNodes = new ArrayList<>();
        List<Set<String>> consolidatedRelatedNodes = new ArrayList<>();
        List<Document> documents = new ArrayList<>();

        String sceneID = XPathAPI.selectSingleNode(source, "/COLLADA/scene/instance_visual_scene").getAttributes().getNamedItem("url").getNodeValue();
        sceneID = sceneID.substring(1, sceneID.length());

        List<String> nodes = XPathAPI.selectListOfNodes(source, "/COLLADA/library_visual_scenes/visual_scene/node")
                .parallelStream()
                .map(node -> node.getAttributes()
                        .getNamedItem("name")
                        .getNodeValue())
                .collect(Collectors.toList());

        for (String node : nodes) {
            XPathAPI.selectListOfNodes(source, "/COLLADA/library_visual_scenes/visual_scene/node[descendant::instance_node[@url='#" + node + "']]")
                    .parallelStream()
                    .map(n -> n.getAttributes()
                            .getNamedItem("id")
                            .getNodeValue())
                    .distinct()
                    .forEach(s -> relatedNodes.add(new Pair<>(node, s)));
        }

        consolidateNodes(relatedNodes, consolidatedRelatedNodes, nodes);

        for (Set<String> sceneNodes : consolidatedRelatedNodes) {
            Document temp = builder.newDocument();
            Node root = createSkeleton(source, temp);

            List<String> geometryIDs = new ArrayList<>();
            Element visualScenes = temp.createElement("library_visual_scenes");
            Element visualScene = temp.createElement("visual_scene");
            visualScene.setAttribute("id", sceneID);
            for (String nodeID : sceneNodes) {
                String geometry = XPathAPI.selectSingleNode(source, "/COLLADA/library_visual_scenes/visual_scene/node[@id='" + nodeID + "']//instance_geometry")
                        .getAttributes()
                        .getNamedItem("url")
                        .getNodeValue();
                geometry = geometry.substring(1, geometry.length());
                geometryIDs.add(geometry);
                visualScene.appendChild(temp.importNode(XPathAPI.selectSingleNode(source, "/COLLADA/library_visual_scenes/visual_scene/node[@id='" + nodeID + "']"), true));
            }
            visualScenes.appendChild(visualScene);
            root.appendChild(visualScenes);

            Element geometries = temp.createElement("library_geometries");
            for (String geometryID : geometryIDs) {
                geometries.appendChild(temp.importNode(XPathAPI.selectSingleNode(source, "/COLLADA/library_geometries/geometry[@id='" + geometryID + "']"), true));
            }
            root.appendChild(geometries);

            temp.appendChild(root);
            documents.add(temp);
        }
        return documents;
    }

    private void writeDocumentToFile(Document document, File file) throws TransformerException {
        DOMSource source = new DOMSource(document);
        StreamResult result = new StreamResult(file);
        transformer.transform(source, result);
    }
}
