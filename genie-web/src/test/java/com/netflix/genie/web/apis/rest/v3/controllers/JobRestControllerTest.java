/*
 *
 *  Copyright 2016 Netflix, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */
package com.netflix.genie.web.apis.rest.v3.controllers;

import com.google.common.collect.Sets;
import com.netflix.genie.common.dto.JobRequest;
import com.netflix.genie.common.dto.JobStatus;
import com.netflix.genie.common.exceptions.GenieException;
import com.netflix.genie.common.exceptions.GenieNotFoundException;
import com.netflix.genie.common.exceptions.GenieServerUnavailableException;
import com.netflix.genie.common.internal.jobs.JobConstants;
import com.netflix.genie.common.internal.util.GenieHostInfo;
import com.netflix.genie.web.agent.services.AgentRoutingService;
import com.netflix.genie.web.apis.rest.v3.hateoas.assemblers.ApplicationResourceAssembler;
import com.netflix.genie.web.apis.rest.v3.hateoas.assemblers.ClusterResourceAssembler;
import com.netflix.genie.web.apis.rest.v3.hateoas.assemblers.CommandResourceAssembler;
import com.netflix.genie.web.apis.rest.v3.hateoas.assemblers.JobExecutionResourceAssembler;
import com.netflix.genie.web.apis.rest.v3.hateoas.assemblers.JobMetadataResourceAssembler;
import com.netflix.genie.web.apis.rest.v3.hateoas.assemblers.JobRequestResourceAssembler;
import com.netflix.genie.web.apis.rest.v3.hateoas.assemblers.JobResourceAssembler;
import com.netflix.genie.web.apis.rest.v3.hateoas.assemblers.JobSearchResultResourceAssembler;
import com.netflix.genie.web.apis.rest.v3.hateoas.assemblers.ResourceAssemblers;
import com.netflix.genie.web.apis.rest.v3.hateoas.assemblers.RootResourceAssembler;
import com.netflix.genie.web.data.services.JobPersistenceService;
import com.netflix.genie.web.data.services.JobSearchService;
import com.netflix.genie.web.properties.JobsProperties;
import com.netflix.genie.web.services.AttachmentService;
import com.netflix.genie.web.services.JobCoordinatorService;
import com.netflix.genie.web.services.JobDirectoryServerService;
import com.netflix.genie.web.services.JobLaunchService;
import com.netflix.genie.web.util.JobExecutionModeSelector;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.catalina.ssi.ByteArrayServletOutputStream;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Unit tests for the Job rest controller.
 *
 * @author tgianos
 * @since 3.0.0
 */
class JobRestControllerTest {

    //Mocked variables
    private JobSearchService jobSearchService;
    private AgentRoutingService agentRoutingService;
    private JobPersistenceService jobPersistenceService;
    private String hostname;
    private RestTemplate restTemplate;
    private JobDirectoryServerService jobDirectoryServerService;
    private JobExecutionModeSelector jobExecutionModeSelector;
    private JobsProperties jobsProperties;
    private Environment environment;

    private JobRestController controller;

    /**
     * Setup for the tests.
     */
    @BeforeEach
    void setup() {
        this.jobSearchService = Mockito.mock(JobSearchService.class);
        this.jobPersistenceService = Mockito.mock(JobPersistenceService.class);
        this.agentRoutingService = Mockito.mock(AgentRoutingService.class);
        this.hostname = UUID.randomUUID().toString();
        this.restTemplate = Mockito.mock(RestTemplate.class);
        this.jobDirectoryServerService = Mockito.mock(JobDirectoryServerService.class);
        this.jobExecutionModeSelector = Mockito.mock(JobExecutionModeSelector.class);
        this.jobsProperties = JobsProperties.getJobsPropertiesDefaults();
        this.environment = Mockito.mock(Environment.class);
        Mockito.when(this.jobExecutionModeSelector.executeWithAgent(
            Mockito.any(JobRequest.class),
            Mockito.any(HttpServletRequest.class))
        ).thenReturn(false);

        final MeterRegistry registry = Mockito.mock(MeterRegistry.class);
        final Counter counter = Mockito.mock(Counter.class);
        Mockito.when(registry.counter(Mockito.anyString())).thenReturn(counter);

        this.controller = new JobRestController(
            Mockito.mock(JobLaunchService.class),
            this.jobSearchService,
            Mockito.mock(JobCoordinatorService.class),
            this.createMockResourceAssembler(),
            new GenieHostInfo(this.hostname),
            this.restTemplate,
            this.jobDirectoryServerService,
            this.jobsProperties,
            registry,
            this.jobPersistenceService,
            this.agentRoutingService,
            this.environment,
            Mockito.mock(AttachmentService.class),
            jobExecutionModeSelector
        );
    }

    /**
     * Make sure if forwarding isn't enabled we don't even try to forward no matter where the job is running.
     *
     * @throws IOException    On error
     * @throws GenieException On Error
     */
    @Test
    void wontForwardKillRequestIfNotEnabled() throws IOException, GenieException {
        this.jobsProperties.getForwarding().setEnabled(false);

        final String jobId = UUID.randomUUID().toString();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        Mockito.when(this.jobPersistenceService.getJobStatus(jobId)).thenReturn(JobStatus.RUNNING);

        this.controller.killJob(jobId, null, request, response);

        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).getJobStatus(jobId);
        Mockito.verify(this.jobPersistenceService, Mockito.never()).isV4(jobId);
        Mockito.verify(this.jobSearchService, Mockito.never()).getJobHost(jobId);
        Mockito.verify(this.agentRoutingService, Mockito.never()).getHostnameForAgentConnection(jobId);
    }

    /**
     * Make sure won't forward job kill request if it's already been forwarded.
     *
     * @throws IOException    on error
     * @throws GenieException on error
     */
    @Test
    void wontForwardJobKillRequestIfAlreadyForwarded() throws IOException, GenieException {
        this.jobsProperties.getForwarding().setEnabled(true);
        final String jobId = UUID.randomUUID().toString();
        final String forwardedFrom = UUID.randomUUID().toString();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);
        Mockito.when(this.jobPersistenceService.getJobStatus(jobId)).thenReturn(JobStatus.RUNNING);

        this.controller.killJob(jobId, forwardedFrom, request, response);

        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).getJobStatus(jobId);
        Mockito.verify(this.jobPersistenceService, Mockito.never()).isV4(jobId);
        Mockito.verify(this.jobSearchService, Mockito.never()).getJobHost(jobId);
        Mockito.verify(this.agentRoutingService, Mockito.never()).getHostnameForAgentConnection(jobId);
    }

    /**
     * Makes sure we don't forward the v3 job kill request if we're already on the right host.
     *
     * @throws IOException    on error
     * @throws GenieException on error
     */
    @Test
    void wontForwardV3JobKillRequestIfOnCorrectHost() throws IOException, GenieException {
        this.jobsProperties.getForwarding().setEnabled(true);
        final String jobId = UUID.randomUUID().toString();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito.when(this.jobPersistenceService.getJobStatus(jobId)).thenReturn(JobStatus.RUNNING);
        Mockito.when(this.jobPersistenceService.isV4(jobId)).thenReturn(false);
        Mockito.when(this.jobSearchService.getJobHost(jobId)).thenReturn(this.hostname);

        this.controller.killJob(jobId, null, request, response);

        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).getJobStatus(jobId);
        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).isV4(jobId);
        Mockito.verify(this.jobSearchService, Mockito.times(1)).getJobHost(jobId);
        Mockito.verify(this.agentRoutingService, Mockito.never()).getHostnameForAgentConnection(jobId);
        Mockito
            .verify(this.restTemplate, Mockito.never())
            .execute(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyString());
    }

    /**
     * Makes sure if we do forward and get back a v3 job kill error we return it to the user.
     *
     * @throws IOException    on error
     * @throws GenieException on error
     */
    @Test
    void canRespondToV3KillRequestForwardError() throws IOException, GenieException {
        this.jobsProperties.getForwarding().setEnabled(true);
        final String jobId = UUID.randomUUID().toString();
        final String host = UUID.randomUUID().toString();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito.when(request.getRequestURL()).thenReturn(new StringBuffer(UUID.randomUUID().toString()));
        Mockito.when(this.jobPersistenceService.getJobStatus(jobId)).thenReturn(JobStatus.RUNNING);
        Mockito.when(this.jobPersistenceService.isV4(jobId)).thenReturn(false);
        Mockito.when(this.jobSearchService.getJobHost(jobId)).thenReturn(host);

        final StatusLine statusLine = Mockito.mock(StatusLine.class);
        Mockito.when(statusLine.getStatusCode()).thenReturn(HttpStatus.NOT_FOUND.value());
        final HttpResponse forwardResponse = Mockito.mock(HttpResponse.class);
        Mockito.when(forwardResponse.getStatusLine()).thenReturn(statusLine);
        Mockito.when(
            this.restTemplate.execute(
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
            )
        )
            .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        this.controller.killJob(jobId, null, request, response);

        Mockito
            .verify(response, Mockito.times(1))
            .sendError(Mockito.eq(HttpStatus.NOT_FOUND.value()), Mockito.anyString());
        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).getJobStatus(jobId);
        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).isV4(jobId);
        Mockito.verify(this.jobSearchService, Mockito.times(1)).getJobHost(jobId);
        Mockito.verify(this.agentRoutingService, Mockito.never()).getHostnameForAgentConnection(jobId);
        Mockito
            .verify(this.restTemplate, Mockito.times(1))
            .execute(
                Mockito.eq("http://" + host + ":8080/api/v3/jobs/" + jobId),
                Mockito.eq(HttpMethod.DELETE),
                Mockito.any(),
                Mockito.any()
            );
    }

    /**
     * Makes sure we can successfully forward a v3 job kill request.
     *
     * @throws IOException    on error
     * @throws GenieException on error
     */
    @Test
    void canForwardV3JobKillRequest() throws IOException, GenieException {
        this.jobsProperties.getForwarding().setEnabled(true);
        final String jobId = UUID.randomUUID().toString();
        final String host = UUID.randomUUID().toString();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito.when(request.getRequestURL()).thenReturn(new StringBuffer(UUID.randomUUID().toString()));
        Mockito.when(this.jobPersistenceService.getJobStatus(jobId)).thenReturn(JobStatus.RUNNING);
        Mockito.when(this.jobPersistenceService.isV4(jobId)).thenReturn(false);
        Mockito.when(this.jobSearchService.getJobHost(jobId)).thenReturn(host);

        final StatusLine statusLine = Mockito.mock(StatusLine.class);
        Mockito.when(statusLine.getStatusCode()).thenReturn(HttpStatus.ACCEPTED.value());
        final HttpResponse forwardResponse = Mockito.mock(HttpResponse.class);
        Mockito.when(forwardResponse.getStatusLine()).thenReturn(statusLine);
        Mockito.when(forwardResponse.getAllHeaders()).thenReturn(new Header[0]);
        Mockito
            .when(
                this.restTemplate.execute(
                    Mockito.anyString(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any()
                )
            )
            .thenReturn(null);

        this.controller.killJob(jobId, null, request, response);

        Mockito.verify(response, Mockito.never()).sendError(Mockito.anyInt(), Mockito.anyString());
        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).getJobStatus(jobId);
        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).isV4(jobId);
        Mockito.verify(this.jobSearchService, Mockito.times(1)).getJobHost(jobId);
        Mockito.verify(this.agentRoutingService, Mockito.never()).getHostnameForAgentConnection(jobId);
        Mockito.verify(this.restTemplate, Mockito.times(1))
            .execute(
                Mockito.eq("http://" + host + ":8080/api/v3/jobs/" + jobId),
                Mockito.eq(HttpMethod.DELETE),
                Mockito.any(),
                Mockito.any()
            );
    }

    /**
     * Makes sure we don't forward the v4 job kill request if we're already on the right host.
     *
     * @throws IOException    on error
     * @throws GenieException on error
     */
    @Test
    void wontForwardV4JobKillRequestIfOnCorrectHost() throws IOException, GenieException {
        this.jobsProperties.getForwarding().setEnabled(true);
        final String jobId = UUID.randomUUID().toString();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito.when(this.jobPersistenceService.getJobStatus(jobId)).thenReturn(JobStatus.RUNNING);
        Mockito.when(this.jobPersistenceService.isV4(jobId)).thenReturn(true);
        Mockito.when(this.agentRoutingService.getHostnameForAgentConnection(jobId))
            .thenReturn(Optional.of(this.hostname));

        this.controller.killJob(jobId, null, request, response);

        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).getJobStatus(jobId);
        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).isV4(jobId);
        Mockito.verify(this.jobSearchService, Mockito.never()).getJobHost(jobId);
        Mockito.verify(this.agentRoutingService, Mockito.times(1)).getHostnameForAgentConnection(jobId);
        Mockito
            .verify(this.restTemplate, Mockito.never())
            .execute(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    /**
     * Makes sure if we do forward and get back a v4 job kill error we return it to the user.
     *
     * @throws IOException    on error
     * @throws GenieException on error
     */
    @Test
    void canRespondToV4KillRequestForwardError() throws IOException, GenieException {
        this.jobsProperties.getForwarding().setEnabled(true);
        final String jobId = UUID.randomUUID().toString();
        final String host = UUID.randomUUID().toString();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito.when(request.getRequestURL()).thenReturn(new StringBuffer(UUID.randomUUID().toString()));
        Mockito.when(this.jobPersistenceService.getJobStatus(jobId)).thenReturn(JobStatus.RUNNING);
        Mockito.when(this.jobPersistenceService.isV4(jobId)).thenReturn(true);
        Mockito.when(this.agentRoutingService.getHostnameForAgentConnection(jobId))
            .thenReturn(Optional.of(host));

        final StatusLine statusLine = Mockito.mock(StatusLine.class);
        Mockito.when(statusLine.getStatusCode()).thenReturn(HttpStatus.NOT_FOUND.value());
        final HttpResponse forwardResponse = Mockito.mock(HttpResponse.class);
        Mockito.when(forwardResponse.getStatusLine()).thenReturn(statusLine);
        Mockito.when(
            this.restTemplate.execute(
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
            )
        )
            .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        this.controller.killJob(jobId, null, request, response);

        Mockito
            .verify(response, Mockito.times(1))
            .sendError(Mockito.eq(HttpStatus.NOT_FOUND.value()), Mockito.anyString());
        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).getJobStatus(jobId);
        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).isV4(jobId);
        Mockito.verify(this.jobSearchService, Mockito.never()).getJobHost(jobId);
        Mockito.verify(this.agentRoutingService, Mockito.times(1)).getHostnameForAgentConnection(jobId);
        Mockito
            .verify(this.restTemplate, Mockito.times(1))
            .execute(
                Mockito.eq("http://" + host + ":8080/api/v3/jobs/" + jobId),
                Mockito.eq(HttpMethod.DELETE),
                Mockito.any(),
                Mockito.any()
            );
    }

    /**
     * Makes sure we can successfully forward a v4 job kill request.
     *
     * @throws IOException    on error
     * @throws GenieException on error
     */
    @Test
    void canForwardV4JobKillRequest() throws IOException, GenieException {
        this.jobsProperties.getForwarding().setEnabled(true);
        final String jobId = UUID.randomUUID().toString();
        final String host = UUID.randomUUID().toString();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito.when(request.getRequestURL()).thenReturn(new StringBuffer(UUID.randomUUID().toString()));
        Mockito.when(this.jobPersistenceService.getJobStatus(jobId)).thenReturn(JobStatus.RUNNING);
        Mockito.when(this.jobPersistenceService.isV4(jobId)).thenReturn(true);
        Mockito.when(this.agentRoutingService.getHostnameForAgentConnection(jobId))
            .thenReturn(Optional.of(host));

        final StatusLine statusLine = Mockito.mock(StatusLine.class);
        Mockito.when(statusLine.getStatusCode()).thenReturn(HttpStatus.ACCEPTED.value());
        final HttpResponse forwardResponse = Mockito.mock(HttpResponse.class);
        Mockito.when(forwardResponse.getStatusLine()).thenReturn(statusLine);
        Mockito.when(forwardResponse.getAllHeaders()).thenReturn(new Header[0]);
        Mockito
            .when(
                this.restTemplate.execute(
                    Mockito.anyString(),
                    Mockito.any(),
                    Mockito.any(),
                    Mockito.any()
                )
            )
            .thenReturn(null);

        this.controller.killJob(jobId, null, request, response);

        Mockito.verify(response, Mockito.never()).sendError(Mockito.anyInt(), Mockito.anyString());
        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).getJobStatus(jobId);
        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).isV4(jobId);
        Mockito.verify(this.jobSearchService, Mockito.never()).getJobHost(jobId);
        Mockito.verify(this.agentRoutingService, Mockito.times(1)).getHostnameForAgentConnection(jobId);
        Mockito.verify(this.restTemplate, Mockito.times(1))
            .execute(
                Mockito.eq("http://" + host + ":8080/api/v3/jobs/" + jobId),
                Mockito.eq(HttpMethod.DELETE),
                Mockito.any(),
                Mockito.any()
            );
    }

    /**
     * Make sure GenieNotFoundException exception thrown on no job found for a job kill request.
     *
     * @throws GenieException on error
     */
    @Test
    void missingJobOnJobKillRequestThrowsException() throws GenieException {
        this.jobsProperties.getForwarding().setEnabled(true);
        final String jobId = UUID.randomUUID().toString();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito.when(this.jobPersistenceService.getJobStatus(jobId)).thenThrow(new GenieNotFoundException("bad"));

        Assertions
            .assertThatExceptionOfType(GenieNotFoundException.class)
            .isThrownBy(() -> this.controller.killJob(jobId, null, request, response));

        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).getJobStatus(jobId);
        Mockito.verify(this.jobPersistenceService, Mockito.never()).isV4(jobId);
        Mockito.verify(this.jobSearchService, Mockito.never()).getJobHost(jobId);
        Mockito.verify(this.agentRoutingService, Mockito.never()).getHostnameForAgentConnection(jobId);
        Mockito
            .verify(this.restTemplate, Mockito.never())
            .execute(
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
            );
    }

    /**
     * Make sure GenieNotFoundException thrown on a missing host name for a v3
     * job kill request gets percolated up.
     *
     * @throws GenieException on error
     */
    @Test
    void exceptionThrownMissingHostNameForV3JobKill() throws GenieException {
        this.jobsProperties.getForwarding().setEnabled(true);
        final String jobId = UUID.randomUUID().toString();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito.when(this.jobPersistenceService.getJobStatus(jobId)).thenReturn(JobStatus.RUNNING);
        Mockito.when(this.jobPersistenceService.isV4(jobId)).thenReturn(false);
        Mockito.when(this.jobSearchService.getJobHost(jobId)).thenThrow(new GenieNotFoundException("Testing"));

        Assertions
            .assertThatExceptionOfType(GenieNotFoundException.class)
            .isThrownBy(() -> this.controller.killJob(jobId, null, request, response));

        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).getJobStatus(jobId);
        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).isV4(jobId);
        Mockito.verify(this.jobSearchService, Mockito.times(1)).getJobHost(jobId);
        Mockito.verify(this.agentRoutingService, Mockito.never()).getHostnameForAgentConnection(jobId);
        Mockito
            .verify(this.restTemplate, Mockito.never())
            .execute(
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
            );
    }

    /**
     * Make sure GenieNotFoundException exception thrown on a missing host name for v4 job kill
     * request gets percolated up.
     *
     * @throws GenieException on error
     */
    @Test
    void exceptionThrownMissingHostNameForV4JobKill() throws GenieException {
        this.jobsProperties.getForwarding().setEnabled(true);
        final String jobId = UUID.randomUUID().toString();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito.when(this.jobPersistenceService.getJobStatus(jobId)).thenReturn(JobStatus.RUNNING);
        Mockito.when(this.jobPersistenceService.isV4(jobId)).thenReturn(true);
        Mockito.when(this.agentRoutingService.getHostnameForAgentConnection(jobId)).thenReturn(Optional.empty());

        Assertions
            .assertThatExceptionOfType(GenieNotFoundException.class)
            .isThrownBy(() -> this.controller.killJob(jobId, null, request, response));

        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).getJobStatus(jobId);
        Mockito.verify(this.jobPersistenceService, Mockito.times(1)).isV4(jobId);
        Mockito.verify(this.jobSearchService, Mockito.never()).getJobHost(jobId);
        Mockito.verify(this.agentRoutingService, Mockito.times(1)).getHostnameForAgentConnection(jobId);
        Mockito
            .verify(this.restTemplate, Mockito.never())
            .execute(
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
            );
    }

    /**
     * Make sure if directory forwarding isn't enabled it never fires.
     *
     * @throws IOException      on error
     * @throws ServletException on error
     * @throws GenieException   on error
     */
    @Test
    void wontForwardJobOutputRequestIfNotEnabled() throws IOException, ServletException, GenieException {
        this.jobsProperties.getForwarding().setEnabled(false);

        final String jobId = UUID.randomUUID().toString();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito
            .when(request.getRequestURL())
            .thenReturn(new StringBuffer("https://localhost:8443/api/v3/jobs/1234/output"));
        Mockito
            .doNothing()
            .when(this.jobDirectoryServerService)
            .serveResource(
                Mockito.eq(jobId),
                Mockito.any(URL.class),
                Mockito.anyString(),
                Mockito.eq(request),
                Mockito.eq(response)
            );
        Mockito.when(this.jobPersistenceService.getJobStatus(jobId)).thenReturn(JobStatus.RUNNING);

        this.controller.getJobOutput(jobId, null, request, response);

        Mockito.verify(this.jobSearchService, Mockito.never()).getJobHost(Mockito.eq(jobId));
        Mockito
            .verify(this.jobDirectoryServerService, Mockito.times(1))
            .serveResource(
                Mockito.eq(jobId),
                Mockito.any(URL.class),
                Mockito.anyString(),
                Mockito.eq(request),
                Mockito.eq(response)
            );
    }

    /**
     * Make sure if directory forwarding doesn't fire if already forwarded.
     *
     * @throws IOException      on error
     * @throws ServletException on error
     * @throws GenieException   on error
     */
    @Test
    void wontForwardJobOutputRequestIfAlreadyForwarded() throws IOException, ServletException, GenieException {
        this.jobsProperties.getForwarding().setEnabled(true);
        final String jobId = UUID.randomUUID().toString();
        final String forwardedFrom = "https://localhost:8443/api/v3/jobs/1234/output";
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito
            .doNothing()
            .when(this.jobDirectoryServerService)
            .serveResource(
                Mockito.eq(jobId),
                Mockito.any(URL.class),
                Mockito.anyString(),
                Mockito.eq(request),
                Mockito.eq(response)
            );
        Mockito.when(this.jobPersistenceService.getJobStatus(jobId)).thenReturn(JobStatus.RUNNING);

        this.controller.getJobOutput(jobId, forwardedFrom, request, response);

        Mockito.verify(this.jobSearchService, Mockito.never()).getJobHost(Mockito.eq(jobId));
        Mockito
            .verify(this.jobDirectoryServerService, Mockito.times(1))
            .serveResource(
                Mockito.eq(jobId),
                Mockito.any(URL.class),
                Mockito.anyString(),
                Mockito.eq(request),
                Mockito.eq(response)
            );
    }

    /**
     * Make sure if directory forwarding doesn't fire if host name matches.
     *
     * @throws IOException      on error
     * @throws ServletException on error
     * @throws GenieException   on error
     */
    @Test
    void wontForwardJobOutputRequestIfOnCorrectHost() throws IOException, ServletException, GenieException {
        this.jobsProperties.getForwarding().setEnabled(true);
        final String jobId = UUID.randomUUID().toString();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito
            .when(request.getRequestURL())
            .thenReturn(new StringBuffer("https://" + this.hostname + "/api/v3/jobs/1234/output"));
        Mockito
            .doNothing()
            .when(this.jobDirectoryServerService)
            .serveResource(
                Mockito.eq(jobId),
                Mockito.any(URL.class),
                Mockito.anyString(),
                Mockito.eq(request),
                Mockito.eq(response)
            );

        Mockito.when(this.jobSearchService.getJobHost(jobId)).thenReturn(this.hostname);
        Mockito.when(this.jobPersistenceService.getJobStatus(jobId)).thenReturn(JobStatus.RUNNING);

        this.controller.getJobOutput(jobId, null, request, response);

        Mockito.verify(this.jobSearchService, Mockito.times(1)).getJobHost(Mockito.eq(jobId));
        Mockito
            .verify(this.restTemplate, Mockito.never())
            .execute(
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any(),
                Mockito.anyString(),
                Mockito.anyString()
            );
        Mockito
            .verify(this.jobDirectoryServerService, Mockito.times(1))
            .serveResource(
                Mockito.eq(jobId),
                Mockito.any(URL.class),
                Mockito.anyString(),
                Mockito.eq(request),
                Mockito.eq(response)
            );
    }

    /**
     * Make sure directory forwarding happens when all conditions are met.
     *
     * @throws IOException      on error
     * @throws ServletException on error
     * @throws GenieException   on error
     */
    @Test
    void canHandleForwardJobOutputRequestWithError() throws IOException, ServletException, GenieException {
        this.jobsProperties.getForwarding().setEnabled(true);
        final String jobId = UUID.randomUUID().toString();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito
            .when(request.getRequestURL())
            .thenReturn(new StringBuffer("http://" + this.hostname + ":8080/api/v3/jobs/1234/output"));
        Mockito
            .doNothing()
            .when(this.jobDirectoryServerService)
            .serveResource(
                Mockito.eq(jobId),
                Mockito.any(URL.class),
                Mockito.anyString(),
                Mockito.eq(request),
                Mockito.eq(response)
            );
        Mockito.when(this.jobPersistenceService.getJobStatus(jobId)).thenReturn(JobStatus.RUNNING);

        final String jobHostName = UUID.randomUUID().toString();
        Mockito.when(this.jobSearchService.getJobHost(jobId)).thenReturn(jobHostName);

        //Mock parts of the http request
        final String http = "http";
        Mockito.when(request.getScheme()).thenReturn(http);
        final int port = 8080;
        Mockito.when(request.getServerPort()).thenReturn(port);
        final String requestURI = "/" + jobId + "/" + UUID.randomUUID().toString();
        Mockito.when(request.getRequestURI()).thenReturn(requestURI);
        Mockito.when(request.getHeaderNames()).thenReturn(null);

        final int errorCode = 404;
        Mockito.when(
            this.restTemplate.execute(
                Mockito.anyString(),
                Mockito.any(),
                Mockito.any(),
                Mockito.any()
            )
        )
            .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        this.controller.getJobOutput(jobId, null, request, response);

        Mockito.verify(this.jobSearchService, Mockito.times(1)).getJobHost(Mockito.eq(jobId));
        Mockito.verify(this.restTemplate, Mockito.times(1))
            .execute(
                Mockito.eq("http://" + jobHostName + ":8080/api/v3/jobs/" + jobId + "/output/"),
                Mockito.eq(HttpMethod.GET),
                Mockito.any(),
                Mockito.any()
            );
        Mockito.verify(response, Mockito.times(1)).sendError(Mockito.eq(errorCode), Mockito.anyString());
        Mockito
            .verify(this.jobDirectoryServerService, Mockito.never())
            .serveResource(
                Mockito.eq(jobId),
                Mockito.any(URL.class),
                Mockito.anyString(),
                Mockito.eq(request),
                Mockito.eq(response)
            );
    }

    /**
     * Make sure directory forwarding happens when all conditions are met.
     *
     * @throws IOException      on error
     * @throws ServletException on error
     * @throws GenieException   on error
     */
    @Test
    void canHandleForwardJobOutputRequestWithSuccess() throws IOException, ServletException, GenieException {
        this.jobsProperties.getForwarding().setEnabled(true);
        final String jobId = UUID.randomUUID().toString();
        final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        final HttpServletResponse response = Mockito.mock(HttpServletResponse.class);

        Mockito
            .when(request.getRequestURL())
            .thenReturn(new StringBuffer("http://localhost:8080/api/v3/jobs/1234/output"));
        Mockito
            .doNothing()
            .when(this.jobDirectoryServerService)
            .serveResource(
                Mockito.eq(jobId),
                Mockito.any(URL.class),
                Mockito.anyString(),
                Mockito.eq(request),
                Mockito.eq(response)
            );
        Mockito.when(this.jobPersistenceService.getJobStatus(jobId)).thenReturn(JobStatus.RUNNING);

        final String jobHostName = UUID.randomUUID().toString();
        Mockito.when(this.jobSearchService.getJobHost(jobId)).thenReturn(jobHostName);

        //Mock parts of the http request
        final String http = "http";
        Mockito.when(request.getScheme()).thenReturn(http);
        final int port = 8080;
        Mockito.when(request.getServerPort()).thenReturn(port);
        final String requestURI = "/" + jobId + "/" + UUID.randomUUID().toString();
        Mockito.when(request.getRequestURI()).thenReturn(requestURI);

        final Set<String> headerNames = Sets.newHashSet(HttpHeaders.ACCEPT);
        Mockito.when(request.getHeaderNames()).thenReturn(Collections.enumeration(headerNames));
        Mockito.when(request.getHeader(HttpHeaders.ACCEPT)).thenReturn(MediaType.APPLICATION_JSON_VALUE);

        //Mock parts of forward response
        final HttpResponse forwardResponse = Mockito.mock(HttpResponse.class);
        final StatusLine statusLine = Mockito.mock(StatusLine.class);
        Mockito.when(forwardResponse.getStatusLine()).thenReturn(statusLine);
        final int successCode = 200;
        Mockito.when(statusLine.getStatusCode()).thenReturn(successCode);
        final Header contentTypeHeader = Mockito.mock(Header.class);
        Mockito.when(contentTypeHeader.getName()).thenReturn(HttpHeaders.CONTENT_TYPE);
        Mockito.when(contentTypeHeader.getValue()).thenReturn(MediaType.TEXT_PLAIN_VALUE);
        Mockito.when(forwardResponse.getAllHeaders()).thenReturn(new Header[]{contentTypeHeader});

        final String text = UUID.randomUUID().toString() + UUID.randomUUID().toString() + UUID.randomUUID().toString();
        final ByteArrayInputStream bis = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        final HttpEntity entity = Mockito.mock(HttpEntity.class);
        Mockito.when(entity.getContent()).thenReturn(bis);
        Mockito.when(forwardResponse.getEntity()).thenReturn(entity);

        final ByteArrayServletOutputStream bos = new ByteArrayServletOutputStream();
        Mockito.when(response.getOutputStream()).thenReturn(bos);

        final ClientHttpRequestFactory factory = Mockito.mock(ClientHttpRequestFactory.class);
        final ClientHttpRequest clientHttpRequest = Mockito.mock(ClientHttpRequest.class);
        Mockito.when(clientHttpRequest.execute())
            .thenReturn(new MockClientHttpResponse(text.getBytes(StandardCharsets.UTF_8), HttpStatus.OK));
        Mockito.when(clientHttpRequest.getHeaders())
            .thenReturn(new HttpHeaders());
        Mockito.when(factory.createRequest(Mockito.any(), Mockito.any())).thenReturn(clientHttpRequest);
        final RestTemplate template = new RestTemplate(factory);
        final MeterRegistry registry = Mockito.mock(MeterRegistry.class);
        final Counter counter = Mockito.mock(Counter.class);
        Mockito.when(registry.counter(Mockito.anyString())).thenReturn(counter);

        final JobRestController jobController = new JobRestController(
            Mockito.mock(JobLaunchService.class),
            this.jobSearchService,
            Mockito.mock(JobCoordinatorService.class),
            this.createMockResourceAssembler(),
            new GenieHostInfo(this.hostname),
            template,
            this.jobDirectoryServerService,
            this.jobsProperties,
            registry,
            this.jobPersistenceService,
            this.agentRoutingService,
            this.environment,
            Mockito.mock(AttachmentService.class),
            this.jobExecutionModeSelector
        );
        jobController.getJobOutput(jobId, null, request, response);

        Assertions.assertThat(new String(bos.toByteArray(), StandardCharsets.UTF_8)).isEqualTo(text);
        Mockito.verify(request, Mockito.times(1)).getHeader(HttpHeaders.ACCEPT);
        Mockito.verify(this.jobSearchService, Mockito.times(1)).getJobHost(Mockito.eq(jobId));
        Mockito.verify(response, Mockito.never()).sendError(Mockito.anyInt());
        Mockito
            .verify(this.jobDirectoryServerService, Mockito.never())
            .serveResource(
                Mockito.eq(jobId),
                Mockito.any(URL.class),
                Mockito.anyString(),
                Mockito.eq(request),
                Mockito.eq(response)
            );
    }

    /**
     * Make sure when job submission is disabled it won't run the job and will return the proper error message.
     */
    @Test
    void whenJobSubmissionIsDisabledItThrowsCorrectError() {
        final String errorMessage = UUID.randomUUID().toString();
        Mockito.when(
            this.environment.getProperty(
                JobConstants.JOB_SUBMISSION_ENABLED_PROPERTY_KEY,
                Boolean.class,
                true
            )
        ).thenReturn(false);
        Mockito.when(
            this.environment.getProperty(
                JobConstants.JOB_SUBMISSION_DISABLED_MESSAGE_KEY,
                JobConstants.JOB_SUBMISSION_DISABLED_DEFAULT_MESSAGE
            )
        ).thenReturn(errorMessage);

        Assertions
            .assertThatExceptionOfType(GenieServerUnavailableException.class)
            .isThrownBy(() ->
                this.controller.submitJob(
                    Mockito.mock(JobRequest.class),
                    null,
                    null,
                    Mockito.mock(HttpServletRequest.class)
                ))
            .withMessage(errorMessage);
    }

    private ResourceAssemblers createMockResourceAssembler() {
        return new ResourceAssemblers(
            Mockito.mock(ApplicationResourceAssembler.class),
            Mockito.mock(ClusterResourceAssembler.class),
            Mockito.mock(CommandResourceAssembler.class),
            Mockito.mock(JobExecutionResourceAssembler.class),
            Mockito.mock(JobMetadataResourceAssembler.class),
            Mockito.mock(JobRequestResourceAssembler.class),
            Mockito.mock(JobResourceAssembler.class),
            Mockito.mock(JobSearchResultResourceAssembler.class),
            Mockito.mock(RootResourceAssembler.class)
        );
    }
}
