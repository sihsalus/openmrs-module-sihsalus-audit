package org.openmrs.module.sihsalusaudit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.openmrs.module.ModuleActivator;
import org.w3c.dom.NodeList;

public class ClinicalAuditModuleDescriptorTest {

    @Test
    public void descriptorProvidesAnInstantiableOpenmrsActivator() throws Exception {
        try (InputStream config = getClass().getResourceAsStream("/config.xml")) {
            assertNotNull("Packaged module descriptor must be available", config);
            NodeList activators = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(config).getElementsByTagName("activator");

            assertEquals("OpenMRS requires one activator declaration", 1, activators.getLength());
            String className = activators.item(0).getTextContent().trim();
            Object activator = Class.forName(className).getConstructor().newInstance();
            assertTrue("Activator must implement the OpenMRS lifecycle contract", activator instanceof ModuleActivator);
        }
    }
}
