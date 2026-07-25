package pro.sketchware.util;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

public class XmlUtil {
    private static final String TAG = "XmlUtil";

    public static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "\\'")
                .replace("\n", "&#10;")
                .replace("\r", "&#13;");
    }

    public static DocumentBuilderFactory newSecureDocumentBuilderFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        // Enable namespace awareness so attributes with prefixes (e.g. android:textColor)
        // are preserved when parsing and serializing.
        factory.setNamespaceAware(true);

        setFeatureIfSupported(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
        setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        setFeatureIfSupported(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setExpandEntityReferences(false);
        try {
            factory.setXIncludeAware(false);
        } catch (UnsupportedOperationException e) {
            LogUtil.w(TAG, "XML parser does not support disabling XInclude", e);
        }
        return factory;
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException e) {
            LogUtil.w(TAG, "XML parser does not support feature: " + feature, e);
        }
    }

    public static String replaceXml(String text) {
        if (text == null) return "";

        // Safer normalization: remove BOM, normalize line endings, and remove only
        // known XML declarations. Do NOT strip spaces/tabs — that can break XML.
        String result = text.replace("\uFEFF", "");
        result = result.replace("\r\n", "\n").replace("\r", "\n");

        result = result.replace("<?xml version=\"1.0\" encoding=\"utf-8\"?>", "")
                .replace("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\" ?>", "");

        return result;
    }

    public static void saveXml(String path, String xml) {
        FileUtil.writeFile(path, xml);
    }

}
