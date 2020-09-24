/*
 * (C) Copyright IBM Corp. 2020 SPDX-License-Identifier: Apache-2.0
 */
package com.linuxforhealth.connect.builder;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linuxforhealth.connect.support.CamelContextSupport;

/**
 * This class builds routes that allow all FHIR and HL7 data posted into LfH to be sent to the configured Health Records Ingestion (HRI) service
 */
public class LfHToHRIRouteBuilder extends RouteBuilder {

    private static final String ROUTE_PROPERTY_NAMESPACE = "lfh.connect.lfhtohri";

    private static final String BATCH_CREATED_ROUTE = "direct:batchCreated";
    private static final String BATCH_ID = "batchId";
    private static final String BEARER_TOKEN_PARM = ROUTE_PROPERTY_NAMESPACE + ".bearer.token";
    private static final String BROKER_PARM = ROUTE_PROPERTY_NAMESPACE + ".brokers";
    private static final String CREATE_BATCH_ROUTE = "direct:createBatch";
    private static final String FHIR_R4_TO_HRI_ROUTE_ID = "fhir-r4-to-hri";
    private static final String FHIR_ROUTE_PARM = ROUTE_PROPERTY_NAMESPACE + ".fhir.uri";
    private static final String HL7_ROUTE_PARM = ROUTE_PROPERTY_NAMESPACE + ".hl7.uri";
    private static final String HL7_TO_HRI_ROUTE_ID = "hl7-to-hri";
    private static final String HRI_ENDPOINT_PARM = ROUTE_PROPERTY_NAMESPACE + ".hri.endpoint";
    private static final String HRI_TOPIC_PARM = ROUTE_PROPERTY_NAMESPACE + ".hri.topic";
    private static final String IBM_CLIENT_ID_PARM = ROUTE_PROPERTY_NAMESPACE + ".ibm.client.id";
    private static final String LFH_PAYLOAD = "lfhPayload";
    private static final Logger LOGGER = LoggerFactory.getLogger(LfHToHRIRouteBuilder.class);
    private static final String MESSAGE_SENT_ROUTE = "direct:messageSent";
    private static final String TOKEN_PARM = ROUTE_PROPERTY_NAMESPACE + ".token";
    private static final String X_IBM_CLIENT_ID = "x-ibm-client-id";

    /**
     * This class processes the current request and updates the exchange to create an HRI batch for the upcoming message.
     */
    private class CreateBatchProcessor implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            CamelContextSupport ctxSupport = new CamelContextSupport(getContext());
            final String bearerToken = ctxSupport.getProperty(BEARER_TOKEN_PARM);
            final String ibmClientId = ctxSupport.getProperty(IBM_CLIENT_ID_PARM);
            final String hriTopic = ctxSupport.getProperty(HRI_TOPIC_PARM);
                    
            Message message = exchange.getIn();
            String exchangeBody = message.getBody(String.class);
            LOGGER.warn("exchangeBody: " + exchangeBody);
            JSONObject jsonObject = new JSONObject(exchangeBody);
            String lfhPayload = jsonObject.getString("data");
            LOGGER.warn("lfhPayload: " + lfhPayload);

            String decodedPayload = new String(Base64.getDecoder().decode(lfhPayload), StandardCharsets.UTF_8);
            LOGGER.warn("decodedPayload: " + decodedPayload);

            exchange.setProperty(LFH_PAYLOAD, decodedPayload);

            String batchName = "LfH-" + System.currentTimeMillis();
            message.setBody("{\"name\": \"" + batchName + "\",\"topic\": \"" + hriTopic + "\",\"dataType\": \"String\"}");
            message.setHeader(Exchange.HTTP_METHOD, "POST");
            message.setHeader(Exchange.CONTENT_TYPE, "application/json");
            message.setHeader(X_IBM_CLIENT_ID, ibmClientId);
            message.setHeader("Authorization", bearerToken);
        }
    }
    /**
     * This class retrieves the batch id generated by the CreateBatch call to HRI, then reverts the exchange message back to the original decoded (fhir/hl7) message.
     */
    private class RevertBodyProcessor implements Processor {
        @Override
        public void process(Exchange exchange) throws Exception {
            Object body = exchange.getMessage().getBody();
            BufferedReader streamReader = new BufferedReader(new InputStreamReader((InputStream) body, "UTF-8"));
            StringBuilder responseStrBuilder = new StringBuilder();

            String inputStr;
            while ((inputStr = streamReader.readLine()) != null) {
                responseStrBuilder.append(inputStr);
            }
            String batchId = new JSONObject(responseStrBuilder.toString()).getString("id");
            LOGGER.warn("New Batch Id: " + batchId);
            exchange.setProperty(BATCH_ID, batchId);

            exchange.getIn().setBody(exchange.getProperty(LFH_PAYLOAD));
        }
    }

    /**
     * Provides base route processing and configuration
     */
    @Override
    public final void configure() {
        CamelContextSupport ctxSupport = new CamelContextSupport(getContext());
        String fromFhirURI = ctxSupport.getProperty(FHIR_ROUTE_PARM);
        String fromHL7URI = ctxSupport.getProperty(HL7_ROUTE_PARM);
        final String brokers = ctxSupport.getProperty(BROKER_PARM);
        final String token = ctxSupport.getProperty(TOKEN_PARM);
        final String bearerToken = ctxSupport.getProperty(BEARER_TOKEN_PARM);
        final String ibmClientId = ctxSupport.getProperty(IBM_CLIENT_ID_PARM);
        final String hriEndpoint = ctxSupport.getProperty(HRI_ENDPOINT_PARM);
        final String hriTopic = ctxSupport.getProperty(HRI_TOPIC_PARM);

        // Route Fhir messages to HRI path
        from(fromFhirURI).routeId(FHIR_R4_TO_HRI_ROUTE_ID).log(LoggingLevel.WARN, LOGGER, "Received message body: ${body}").to(CREATE_BATCH_ROUTE);
        // Route Fhir messages to HRI path
        from(fromHL7URI).routeId(HL7_TO_HRI_ROUTE_ID).log(LoggingLevel.WARN, LOGGER, "Received message body: ${body}").to(CREATE_BATCH_ROUTE);

        // Create new HRI batch and then send to batchCreated route
        from(CREATE_BATCH_ROUTE).process(new CreateBatchProcessor()).to(hriEndpoint + "batches?httpMethod=POST").process(new RevertBodyProcessor()).to(BATCH_CREATED_ROUTE);

        final String toHRIKafka = "kafka:" + hriTopic + "?" 
                + "brokers=" + brokers 
                + "&saslMechanism=PLAIN" 
                + "&securityProtocol=SASL_SSL" 
                + "&sslProtocol=TLSv1.2" 
                + "&sslEnabledProtocols=TLSv1.2"
                + "&sslEndpointAlgorithm=HTTPS" 
                + "&saslJaasConfig=org.apache.kafka.common.security.plain.PlainLoginModule required username=\"token\" password=\"" + token + "\";";

        // Post LfH message to HRI
        from(BATCH_CREATED_ROUTE).to(toHRIKafka).to(MESSAGE_SENT_ROUTE);

        // sendComplete for Batch
        from(MESSAGE_SENT_ROUTE)
            .setBody(constant("{\"recordCount\": 1}"))
            .setHeader(Exchange.HTTP_METHOD, constant("PUT"))
            .setHeader(Exchange.CONTENT_TYPE, constant("application/json"))
            .setHeader(X_IBM_CLIENT_ID, constant(ibmClientId))
            .setHeader("Authorization", constant(bearerToken))
            .toD(hriEndpoint + "batches/${exchangeProperty." + BATCH_ID + "}/action/sendComplete");
    }
}