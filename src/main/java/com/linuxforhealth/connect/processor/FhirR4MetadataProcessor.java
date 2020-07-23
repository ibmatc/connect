/*
 * (C) Copyright IBM Corp. 2020
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.linuxforhealth.connect.processor;


import ca.uhn.fhir.context.FhirContext;
import java.time.Instant;
import java.util.UUID;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.builder.SimpleBuilder;
import org.hl7.fhir.r4.model.Resource;

/**
 * Set the headers used by downstream processors and components
 */
public class FhirR4MetadataProcessor implements Processor {

    @Override
    public void process(Exchange exchange)  {
        String fhirBaseUri = SimpleBuilder
                .simple("{{lfh.connect.fhir_r4_rest.uri}}")
                .evaluate(exchange, String.class);

        String resourceType = exchange.getIn().getHeader("resource", String.class);
        String kafkaDataStoreUri = SimpleBuilder
                .simple("{{lfh.connect.datastore.uri}}")
                .evaluate(exchange, String.class)
                .replaceAll("<topicName>", "FHIR_R4_" + resourceType);

        String routeUrl = fhirBaseUri+"/"+resourceType;

        exchange.setProperty("timestamp", Instant.now().getEpochSecond());
        exchange.setProperty("routeUrl", routeUrl);
        exchange.setProperty("dataStoreUri", kafkaDataStoreUri);
        exchange.setProperty("dataFormat", "fhir-r4");
        exchange.setProperty("uuid", UUID.randomUUID());
        exchange.setProperty("resourceType", resourceType);

        Resource resource = (Resource) exchange.getIn().getBody();
        String result = FhirContext.forR4().newJsonParser().encodeResourceToString(resource);
        exchange.getIn().setBody(result);
    }
}
