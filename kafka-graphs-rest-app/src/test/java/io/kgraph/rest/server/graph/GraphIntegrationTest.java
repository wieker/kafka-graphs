package io.kgraph.rest.server.graph;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.util.Map;

import org.apache.kafka.streams.integration.utils.EmbeddedKafkaCluster;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.MultiValueMap;

import io.kgraph.GraphAlgorithmState;
import io.kgraph.rest.server.KafkaGraphsApplication;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@RunWith(SpringRunner.class)
@AutoConfigureWebTestClient(timeout = "36000")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = KafkaGraphsApplication.class)
public class GraphIntegrationTest {

    @ClassRule
    public static final EmbeddedKafkaCluster CLUSTER = new EmbeddedKafkaCluster(1) {
        @Override
        public void start() throws IOException, InterruptedException {
            super.start();
            System.setProperty("spring.embedded.kafka.brokers", bootstrapServers());
            System.setProperty("spring.embedded.zookeeper.connect", zKConnectString());
        }
    };

    @Autowired
    private WebTestClient webTestClient;

    @Test
    public void testConnectedComponents() {
        webTestClient
            .post()
            .uri("/import")
            .syncBody(generateBody())
            .exchange()
            .expectStatus().isOk()
            .expectBody(Void.class);

        GroupEdgesBySourceRequest prepareRequest = new GroupEdgesBySourceRequest();
        prepareRequest.setInitialVerticesTopic("initial-vertices");
        prepareRequest.setInitialEdgesTopic("initial-edges");
        prepareRequest.setVerticesTopic("new-vertices");
        prepareRequest.setEdgesGroupedBySourceTopic("new-edges");
        prepareRequest.setAsync(false);

        webTestClient
            .post()
            .uri("/prepare")
            .contentType(MediaType.APPLICATION_JSON)
            .syncBody(prepareRequest)
            .exchange()
            .expectStatus().isOk()
            .expectBody(Void.class);

        GraphAlgorithmCreateRequest createRequest = new GraphAlgorithmCreateRequest();
        createRequest.setAlgorithm(GraphAlgorithmType.connectedComponents);
        createRequest.setVerticesTopic("new-vertices");
        createRequest.setEdgesGroupedBySourceTopic("new-edges");

        EntityExchangeResult<GraphAlgorithmId> createResponse = webTestClient
            .post()
            .uri("/pregel")
            .contentType(MediaType.APPLICATION_JSON)
            .syncBody(createRequest)
            .exchange()
            .expectStatus().isOk()
            .expectBody(GraphAlgorithmId.class)
            .returnResult();
        String id = createResponse.getResponseBody().getId();

        GraphAlgorithmRunRequest runRequest = new GraphAlgorithmRunRequest();

        EntityExchangeResult<GraphAlgorithmStatus> runResponse = webTestClient
            .post()
            .uri("/pregel/{id}", id)
            .contentType(MediaType.APPLICATION_JSON)
            .syncBody(runRequest)
            .exchange()
            .expectStatus().isOk()
            .expectBody(GraphAlgorithmStatus.class)
            .returnResult();

        GraphAlgorithmState.State state = GraphAlgorithmState.State.RUNNING;
        while (state != GraphAlgorithmState.State.COMPLETED) {
            EntityExchangeResult<GraphAlgorithmStatus> statusResponse = webTestClient
                .get()
                .uri("/pregel/{id}", id)
                .exchange()
                .expectStatus().isOk()
                .expectBody(GraphAlgorithmStatus.class)
                .returnResult();
            state = statusResponse.getResponseBody().getState();
        }

        FluxExchangeResult<String> result = webTestClient
            .get()
            .uri("/pregel/{id}/result", id)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .exchange()
            .expectStatus().isOk()
            .returnResult(String.class);

        Map<String, String> map = result.getResponseBody().collectMap(s -> s.split(" ")[0], s -> s.split(" ")[1]).block();
        for (int i = 0; i < 10; i++) {
            assertEquals("0", map.get(String.valueOf(i)));
        }
        for (int i = 10; i < 21; i++) {
            assertEquals("10", map.get(String.valueOf(i)));
        }
    }

    private MultiValueMap<String, HttpEntity<?>> generateBody() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("verticesFile", new ClassPathResource("vertices_simple.txt"));
        builder.part("edgesFile", new ClassPathResource("edges_simple.txt"));
        builder.part("verticesTopic", "initial-vertices");
        builder.part("edgesTopic", "initial-edges");
        builder.part("useDouble", "false");
        builder.part("numPartitions", "50");
        builder.part("replicationFactor", "1");
        return builder.build();
    }
}