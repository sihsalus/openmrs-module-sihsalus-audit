package org.openmrs.module.sihsalusaudit.web.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.w3c.dom.NodeList;

public class RestErrorSanitizingFilterTest {

    private final RestErrorSanitizingFilter filter = new RestErrorSanitizingFilter();

    @Test
    public void moduleMapsSanitizerToAuditEndpointWithOrWithoutTrailingSlash() throws Exception {
        try (InputStream config = getClass().getResourceAsStream("/config.xml")) {
            assertTrue("Packaged config.xml must be available", config != null);
            NodeList patterns = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(config)
                    .getElementsByTagName("url-pattern");

            assertEquals(2, patterns.getLength());
            assertEquals("/ws/rest/v1/sihsalus/audit", patterns.item(0).getTextContent().trim());
            assertEquals("/ws/rest/v1/sihsalus/audit/*", patterns.item(1).getTextContent().trim());
        }
    }

    @Test
    public void rejectsNonJsonPostsBeforeTheGenericRestFallback() throws Exception {
        for (String contextPath : Arrays.asList("", "/openmrs")) {
            for (String suffix : Arrays.asList("", "/")) {
                for (String contentType : Arrays.asList(null, "text/plain", "application/xml",
                        "application/problem+json", "application/*", "*/*", "invalid-media-type",
                        "application/json; charset=invalid-charset")) {
                    MockHttpServletRequest request = auditRequest("POST", contextPath, suffix);
                    request.setContentType(contentType);
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    AtomicBoolean fallbackInvoked = new AtomicBoolean();

                    filter.doFilter(request, response, (incoming, wrappedResponse) -> {
                        fallbackInvoked.set(true);
                        // RESTWS' generic resource handler otherwise turns this mismatch into 500.
                        ((HttpServletResponse) wrappedResponse).sendError(500, "generic REST fallback");
                    });

                    assertSanitizedError(response, 415, "UNSUPPORTED_MEDIA_TYPE");
                    assertFalse("Unsupported media types must not reach RESTWS", fallbackInvoked.get());
                    assertNull(response.getHeader("Allow"));
                }
            }
        }
    }

    @Test
    public void rejectsUnsupportedMethodsBeforeTheGenericRestFallback() throws Exception {
        for (String contextPath : Arrays.asList("", "/openmrs")) {
            for (String suffix : Arrays.asList("", "/")) {
                for (String method : Arrays.asList("DELETE", "PUT", "PATCH", "TRACE")) {
                    MockHttpServletRequest request = auditRequest(method, contextPath, suffix);
                    request.setContentType("text/plain");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    AtomicBoolean fallbackInvoked = new AtomicBoolean();

                    filter.doFilter(request, response, (incoming, wrappedResponse) -> {
                        fallbackInvoked.set(true);
                        // RESTWS' generic resource handler otherwise reports a missing resource.
                        ((HttpServletResponse) wrappedResponse).sendError(404, "generic REST fallback");
                    });

                    assertSanitizedError(response, 405, "METHOD_NOT_ALLOWED");
                    assertEquals("GET, HEAD, POST, OPTIONS", response.getHeader("Allow"));
                    assertFalse("Unsupported methods must not reach RESTWS", fallbackInvoked.get());
                }
            }
        }
    }

    @Test
    public void passesJsonPostsWithCharsetToTheExistingChainWithoutReadingTheirBody() throws Exception {
        for (String suffix : Arrays.asList("", "/")) {
            for (String contentType : Arrays.asList("application/json", "application/json; charset=UTF-8",
                    "Application/JSON; charset=\"utf-8\"")) {
                MockHttpServletRequest request = auditRequest("POST", "/openmrs", suffix);
                request.setContentType(contentType);
                request.setContent("[]".getBytes(StandardCharsets.UTF_8));
                MockHttpServletResponse response = new MockHttpServletResponse();
                AtomicBoolean chainInvoked = new AtomicBoolean();

                filter.doFilter(request, response, (incoming, wrappedResponse) -> {
                    chainInvoked.set(true);
                    assertSame(request, incoming);
                    assertEquals('[', incoming.getInputStream().read());
                    assertEquals(']', incoming.getInputStream().read());
                    HttpServletResponse http = (HttpServletResponse) wrappedResponse;
                    http.setStatus(200);
                    http.getWriter().write("{\"accepted\":[]}");
                });

                assertTrue(chainInvoked.get());
                assertEquals(200, response.getStatus());
                assertEquals("{\"accepted\":[]}", response.getContentAsString(StandardCharsets.UTF_8));
                assertEquals("no-store", response.getHeader("Cache-Control"));
            }
        }
    }

    @Test
    public void preservesAuthenticationForOtherwiseValidAuditRequests() throws Exception {
        for (String suffix : Arrays.asList("", "/")) {
            for (String method : Arrays.asList("GET", "POST")) {
                MockHttpServletRequest request = auditRequest(method, "/openmrs", suffix);
                request.setContentType("application/json; charset=UTF-8");
                MockHttpServletResponse response = new MockHttpServletResponse();

                filter.doFilter(request, response, (incoming, wrappedResponse) -> {
                    HttpServletResponse http = (HttpServletResponse) wrappedResponse;
                    http.setHeader("WWW-Authenticate", "Basic realm=\"OpenMRS\"");
                    http.sendError(401, "internal authentication detail");
                });

                assertSanitizedError(response, 401, "UNAUTHORIZED");
                assertEquals("Basic realm=\"OpenMRS\"", response.getHeader("WWW-Authenticate"));
                assertFalse(response.getContentAsString().contains("internal authentication detail"));
            }
        }
    }

    @Test
    public void leavesGetHeadAndOptionsHandlingToTheExistingChain() throws Exception {
        for (String suffix : Arrays.asList("", "/")) {
            for (String method : Arrays.asList("GET", "HEAD", "OPTIONS")) {
                MockHttpServletRequest request = auditRequest(method, "/openmrs", suffix);
                request.setContentType("text/plain");
                MockHttpServletResponse response = new MockHttpServletResponse();

                filter.doFilter(request, response, (incoming, wrappedResponse) -> {
                    HttpServletResponse http = (HttpServletResponse) wrappedResponse;
                    http.setHeader("Allow", "GET, HEAD, POST, OPTIONS");
                    http.setStatus(204);
                });

                assertEquals(204, response.getStatus());
                assertEquals("GET, HEAD, POST, OPTIONS", response.getHeader("Allow"));
            }
        }
    }

    @Test
    public void doesNotApplyTheAuditContractToSubpathsOrOtherRoutes() throws Exception {
        for (String contextPath : Arrays.asList("", "/openmrs")) {
            for (String path : Arrays.asList("/ws/rest/v1/sihsalus/audit/event-id",
                    "/ws/rest/v1/sihsalus/audit//", "/ws/rest/v1/sihsalus/audits",
                    "/ws/rest/v1/patient", "/another/ws/rest/v1/sihsalus/audit")) {
                for (String method : Arrays.asList("POST", "DELETE")) {
                    MockHttpServletRequest request = new MockHttpServletRequest(method, contextPath + path);
                    request.setContextPath(contextPath);
                    request.setContentType("text/plain");
                    MockHttpServletResponse response = new MockHttpServletResponse();

                    filter.doFilter(request, response, (incoming, wrappedResponse) -> {
                        HttpServletResponse http = (HttpServletResponse) wrappedResponse;
                        http.setStatus(202);
                        http.getWriter().write("downstream response");
                    });

                    assertEquals(202, response.getStatus());
                    assertEquals("downstream response", response.getContentAsString(StandardCharsets.UTF_8));
                }
            }
        }
    }

    @Test
    public void preservesDownstreamAllowedMethodsWhenSanitizingAnError() throws Exception {
        MockHttpServletRequest request = auditRequest("POST", "/openmrs", "/event-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (incoming, wrappedResponse) -> {
            HttpServletResponse http = (HttpServletResponse) wrappedResponse;
            http.setHeader("Allow", "GET, HEAD");
            http.sendError(405, "internal handler detail");
        });

        assertSanitizedError(response, 405, "METHOD_NOT_ALLOWED");
        assertEquals("GET, HEAD", response.getHeader("Allow"));
        assertFalse(response.getContentAsString().contains("internal handler detail"));
    }

    @Test
    public void replacesLeakingServerErrorBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (request, wrappedResponse) -> {
            HttpServletResponse http = (HttpServletResponse) wrappedResponse;
            http.setHeader("Content-Encoding", "gzip");
            http.setHeader("ETag", "leaking-representation-tag");
            http.setStatus(500);
            http.getWriter().write("java.sql.SQLException at /srv/openmrs/PatientDao.java:77");
        });

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(500, response.getStatus());
        assertTrue(body.contains("SERVER_ERROR"));
        assertFalse(body.contains("SQLException"));
        assertFalse(body.contains("/srv/openmrs"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertNull(response.getHeader("Content-Encoding"));
        assertNull(response.getHeader("ETag"));
    }

    @Test
    public void sanitizesSendErrorMessage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response,
                (request, wrappedResponse) -> ((HttpServletResponse) wrappedResponse)
                        .sendError(403, "Missing privilege: secret-role"));

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(403, response.getStatus());
        assertTrue(body.contains("FORBIDDEN"));
        assertFalse(body.contains("secret-role"));
    }

    @Test
    public void preservesAuthenticationChallengeOnSanitizedUnauthorizedResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (request, wrappedResponse) -> {
            HttpServletResponse http = (HttpServletResponse) wrappedResponse;
            http.setHeader("WWW-Authenticate", "Basic realm=\"OpenMRS\"");
            http.sendError(401, "internal authentication detail");
        });

        assertEquals(401, response.getStatus());
        assertEquals("Basic realm=\"OpenMRS\"", response.getHeader("WWW-Authenticate"));
        assertFalse(response.getContentAsString().contains("internal authentication detail"));
    }

    @Test
    public void preservesSuccessfulResponseBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (request, wrappedResponse) -> {
            HttpServletResponse http = (HttpServletResponse) wrappedResponse;
            http.setStatus(200);
            http.setContentType("application/json");
            http.getWriter().write("{\"ok\":true}");
        });

        assertEquals(200, response.getStatus());
        assertEquals("{\"ok\":true}", response.getContentAsString(StandardCharsets.UTF_8));
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @Test
    public void restoresNoStoreAfterADownstreamResponseReset() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (request, wrappedResponse) -> {
            HttpServletResponse http = (HttpServletResponse) wrappedResponse;
            http.reset();
            http.setStatus(200);
            http.getWriter().write("{\"ok\":true}");
        });

        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @Test
    public void preservesLargeSuccessfulResponseWithinSafetyBound() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        byte[] payload = new byte[70 * 1024];
        Arrays.fill(payload, (byte) 'a');

        filter.doFilter(new MockHttpServletRequest(), response, (request, wrappedResponse) -> {
            HttpServletResponse http = (HttpServletResponse) wrappedResponse;
            http.setStatus(200);
            http.getOutputStream().write(payload);
        });

        assertEquals(payload.length, response.getContentAsByteArray().length);
        assertEquals('a', response.getContentAsByteArray()[0]);
    }

    @Test
    public void preservesLargeSuccessfulWriterResponseWithinSafetyBound() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        char[] chars = new char[70 * 1024];
        Arrays.fill(chars, 'b');
        String payload = new String(chars);

        filter.doFilter(new MockHttpServletRequest(), response, (request, wrappedResponse) -> {
            HttpServletResponse http = (HttpServletResponse) wrappedResponse;
            http.setStatus(200);
            http.getWriter().write(payload);
        });

        assertEquals(payload, response.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    public void sanitizesResponseThatExceedsTheEndpointSafetyBound() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        byte[] payload = new byte[513 * 1024];
        Arrays.fill(payload, (byte) 's');

        filter.doFilter(new MockHttpServletRequest(), response, (request, wrappedResponse) ->
                ((HttpServletResponse) wrappedResponse).getOutputStream().write(payload));

        assertEquals(500, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("SERVER_ERROR"));
        assertFalse(response.getContentAsString(StandardCharsets.UTF_8).contains("ssssssss"));
    }

    @Test
    public void resetBufferRemovesEarlierSuccessfulBody() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, (request, wrappedResponse) -> {
            HttpServletResponse http = (HttpServletResponse) wrappedResponse;
            http.getWriter().write("sensitive stale body");
            http.resetBuffer();
            http.getWriter().write("replacement");
        });

        assertEquals("replacement", response.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    public void convertsUnhandledExceptionToSanitizedServerError() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        IllegalStateException failure = new IllegalStateException("jdbc:mysql://secret-host/openmrs");

        filter.doFilter(new MockHttpServletRequest(), response, (request, wrappedResponse) -> {
            throw failure;
        });

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        Throwable safeFailure = RestErrorSanitizingFilter.safeFailureForLogging(failure);
        StringWriter safeLog = new StringWriter();
        safeFailure.printStackTrace(new PrintWriter(safeLog));
        assertEquals(500, response.getStatus());
        assertTrue(body.contains("SERVER_ERROR"));
        assertFalse(body.contains("secret-host"));
        assertTrue(safeLog.toString().contains(IllegalStateException.class.getName()));
        assertTrue(safeLog.toString().contains("convertsUnhandledExceptionToSanitizedServerError"));
        assertFalse(safeLog.toString().contains("secret-host"));
        assertNull(safeFailure.getCause());
    }

    private MockHttpServletRequest auditRequest(String method, String contextPath, String suffix) {
        MockHttpServletRequest request = new MockHttpServletRequest(method,
                contextPath + "/ws/rest/v1/sihsalus/audit" + suffix);
        request.setContextPath(contextPath);
        return request;
    }

    private void assertSanitizedError(MockHttpServletResponse response, int status, String code) throws Exception {
        assertEquals(status, response.getStatus());
        assertTrue(response.getContentAsString(StandardCharsets.UTF_8).contains("\"code\":\"" + code + "\""));
        assertFalse(response.getContentAsString(StandardCharsets.UTF_8).contains("generic REST fallback"));
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
    }
}
