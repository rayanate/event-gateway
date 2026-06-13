package com.charlesschwab.eventGateway;

import com.charlesschwab.eventGateway.model.EventRecord;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import org.springframework.http.HttpMethod;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public class EventRecordIntegrationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private RestTemplate rest;

    private MockRestServiceServer mockServer;

    @Test
    void createWithoutIdAndTimestamp_thenGetAndListByAccountSorted() {
        String base = "http://localhost:" + port;
        // prepare mock Account Service response for account acct-test
        mockServer = MockRestServiceServer.createServer(rest);
        // for each event: validate (GET) then apply transaction (POST)
        mockServer.expect(requestTo("http://localhost:8082/accounts/acct-test"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK));
        mockServer.expect(requestTo("http://localhost:8082/accounts/acct-test/transactions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK));
        mockServer.expect(requestTo("http://localhost:8082/accounts/acct-test"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.OK));
        mockServer.expect(requestTo("http://localhost:8082/accounts/acct-test/transactions"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.OK));
        // create two events for the same account with different timestamps
        Map<String, Object> body1 = new HashMap<>();
        body1.put("accountId", "acct-test");
        body1.put("eventTimestamp", "2024-01-01T12:00:00Z");
        body1.put("metadata", Map.of("a", "1"));

        Map<String, Object> body2 = new HashMap<>();
        body2.put("accountId", "acct-test");
        body2.put("eventTimestamp", "2025-01-01T12:00:00Z");
        body2.put("metadata", Map.of("b", "2"));

        // use a separate RestTemplate for client calls to the application so the MockRestServiceServer
        // attached to the autowired RestTemplate doesn't intercept them
        RestTemplate client = new RestTemplate();
        ResponseEntity<EventRecord> r1 = client.postForEntity(base + "/events", body1, EventRecord.class);
        ResponseEntity<EventRecord> r2 = client.postForEntity(base + "/events", body2, EventRecord.class);

        Assertions.assertEquals(HttpStatus.CREATED, r1.getStatusCode());
        Assertions.assertEquals(HttpStatus.CREATED, r2.getStatusCode());

        EventRecord e1 = r1.getBody();
        EventRecord e2 = r2.getBody();

        Assertions.assertNotNull(e1);
        Assertions.assertNotNull(e2);
        Assertions.assertNotNull(e1.getEventId());
        Assertions.assertNotNull(e1.getEventTimestamp());

        // GET by id
        ResponseEntity<EventRecord> got = client.getForEntity(base + "/events/" + e1.getEventId(), EventRecord.class);
        Assertions.assertEquals(HttpStatus.OK, got.getStatusCode());
        Assertions.assertEquals(e1.getEventId(), got.getBody().getEventId());

        // list by account and expect e2 (2025) before e1 (2024)
        ResponseEntity<List<EventRecord>> listResp = client.exchange(
                base + "/events?account=acct-test",
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<EventRecord>>() {}
        );

        Assertions.assertEquals(HttpStatus.OK, listResp.getStatusCode());
        List<EventRecord> list = listResp.getBody();
        Assertions.assertNotNull(list);
        Assertions.assertTrue(list.size() >= 2);
        // find positions
        int idxE2 = -1, idxE1 = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getEventId().equals(e2.getEventId())) idxE2 = i;
            if (list.get(i).getEventId().equals(e1.getEventId())) idxE1 = i;
        }
        Assertions.assertTrue(idxE2 >= 0 && idxE1 >= 0 && idxE2 < idxE1, "expected e2 before e1 in sorted list");
    }
}

