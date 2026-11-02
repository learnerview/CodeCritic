package com.codecritic.exec;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class TestResultXmlParser {

    private static final int MAX_MESSAGE_LENGTH = 500;

    public ParsedTestResults parse(List<Path> resultRoots) {
        List<Path> files = findResultFiles(resultRoots);
        if (files.isEmpty()) {
            return ParsedTestResults.empty();
        }

        int testsRun = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        List<TestFailure> failedTests = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Path file : files) {
            try {
                Document document = parseDocument(file);
                NodeList cases = document.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    Element testCase = (Element) cases.item(i);
                    testsRun++;

                    Element failure = directChild(testCase, "failure");
                    Element error = directChild(testCase, "error");
                    Element skippedElement = directChild(testCase, "skipped");
                    boolean isSkipped = skippedElement != null
                            || "true".equalsIgnoreCase(testCase.getAttribute("skipped"));

                    if (failure != null) {
                        failures++;
                        failedTests.add(testFailure(testCase, failure));
                    } else if (error != null) {
                        errors++;
                        failedTests.add(testFailure(testCase, error));
                    } else if (isSkipped) {
                        skipped++;
                    }
                }
            } catch (ParserConfigurationException | SAXException | IOException e) {
                warnings.add("Unable to parse test result " + file + ": " + e.getMessage());
            }
        }

        return new ParsedTestResults(
                files.size(),
                testsRun,
                failures,
                errors,
                skipped,
                failedTests,
                warnings);
    }

    private static List<Path> findResultFiles(List<Path> resultRoots) {
        List<Path> files = new ArrayList<>();
        for (Path root : resultRoots) {
            if (root == null || !Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> {
                            String name = path.getFileName().toString();
                            return name.startsWith("TEST-") && name.endsWith(".xml");
                        })
                        .forEach(files::add);
            } catch (IOException | java.io.UncheckedIOException e) {
                // A missing or unreadable result directory means there is no evidence to parse.
            }
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    private static Document parseDocument(Path file)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new DefaultHandler() {
            @Override
            public void error(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void warning(SAXParseException exception) {
                // A warning does not invalidate the test counts we can still read.
            }
        });
        return builder.parse(file.toFile());
    }

    private static TestFailure testFailure(Element testCase, Element failure) {
        String className = testCase.getAttribute("classname");
        String testName = testCase.getAttribute("name");
        String message = failure.getAttribute("message");
        if (message == null || message.isBlank()) {
            message = failure.getTextContent();
        }
        if (message == null) {
            message = "";
        }
        message = message.strip().replaceAll("\\s+", " ");
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH) + "...";
        }
        return new TestFailure(className, testName, message);
    }

    private static Element directChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(child.getNodeName())) {
                return (Element) child;
            }
        }
        return null;
    }
}
