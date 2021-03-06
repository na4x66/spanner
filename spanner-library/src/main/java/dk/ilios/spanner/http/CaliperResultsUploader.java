/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.ilios.spanner.http;

import com.google.common.base.Optional;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.UniformInterfaceException;
import com.sun.jersey.api.client.WebResource;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import dk.ilios.spanner.config.InvalidConfigurationException;
import dk.ilios.spanner.config.ResultProcessorConfig;
import dk.ilios.spanner.log.StdOut;
import dk.ilios.spanner.model.Trial;
import dk.ilios.spanner.output.ResultProcessor;

import static java.util.logging.Level.SEVERE;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;

/**
 * {@link ResultProcessor} implementation that uploads the JSON-serialized results to the Caliper
 * webapp at https://microbenchmarks.appspot.com/
 */
abstract class CaliperResultsUploader implements ResultProcessor {
    private static final Logger logger = Logger.getLogger(CaliperResultsUploader.class.getName());
    private static final String POST_PATH = "/data/trials";
    private static final String RESULTS_PATH_PATTERN = "/runs/%s";

    private final StdOut stdout;
    private final Client client;
    private final Gson gson;
    private final Optional<UUID> apiKey;
    private final Optional<URI> uploadUri;
    private Optional<UUID> runId = Optional.absent();
    private boolean failure = false;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    CaliperResultsUploader(StdOut stdout, Gson gson, Client client, ResultProcessorConfig resultProcessorConfig) throws InvalidConfigurationException {
        this.stdout = stdout;
        this.client = client;
        this.gson = gson;
        String apiKeyString = resultProcessorConfig.options().get("key");
        Optional<UUID> apiKey = Optional.absent();
        if (Strings.isNullOrEmpty(apiKeyString)) {
            logger.info("No api key specified. Uploading results anonymously.");
        } else {
            try {
                apiKey = Optional.of(UUID.fromString(apiKeyString));
            } catch (IllegalArgumentException e) {
                throw new InvalidConfigurationException(String.format(
                        "The specified API key (%s) is not valid. API keys are UUIDs and should look like %s.",
                        apiKeyString, new UUID(0L, 0L)));
            }
        }
        this.apiKey = apiKey;

        String urlString = resultProcessorConfig.options().get("url");
        if (Strings.isNullOrEmpty(urlString)) {
            logger.info("No upload URL was specified. Results will not be uploaded.");
            this.uploadUri = Optional.absent();
        } else {
            try {
                this.uploadUri = Optional.of(new URI(urlString).resolve(POST_PATH));
            } catch (URISyntaxException e) {
                throw new InvalidConfigurationException(urlString + " is an invalid upload url", e);
            }
        }
    }

    @Override
    public final void processTrial(final Trial trial) {
        if (uploadUri.isPresent()) {
            WebResource resource = client.resource(uploadUri.get());
            if (apiKey.isPresent()) {
                resource = resource.queryParam("key", apiKey.get().toString());
            }
            boolean threw = true;
            try {
                // FIXME Cheating the Android kill switch when doing network on the UI thread.
                final WebResource finalResource = resource;
                final Exception[] threadException = new Exception[1];
                final CountDownLatch threadDone = new CountDownLatch(1);
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // TODO(gak): make the json part happen automagically
                            finalResource.type(APPLICATION_JSON_TYPE).post(gson.toJson(ImmutableList.of(trial)));
                            // only set the run id if a result has been successfully uploaded
                            runId = Optional.of(trial.run().id());
                        } catch (Exception e) {
                            threadException[0] = e;
                        } finally {
                            threadDone.countDown();
                        }
                    }
                });
                threadDone.await(10, TimeUnit.SECONDS);
                threw = false;

            } catch (ClientHandlerException e) {
                logUploadFailure(trial, e);
            } catch (UniformInterfaceException e) {
                logUploadFailure(trial, e);
                logger.fine("Failed upload response: " + e.getResponse().getStatus());
            } catch (InterruptedException e) {
                logUploadFailure(trial, e);
            } finally {
                failure |= threw;
            }
        }
    }

    private static void logUploadFailure(Trial trial, Exception e) {
        logger.log(SEVERE, String.format(
                "Could not upload trial %s. Consider uploading it manually.", trial.id()), e);
    }

    @Override
    public final void close() {
        if (uploadUri.isPresent()) {
            if (runId.isPresent()) {
                stdout.printf("Results have been uploaded. View them at: %s%n",
                        uploadUri.get().resolve(String.format(RESULTS_PATH_PATTERN, runId.get())));
            }
            if (failure) {
                // TODO(gak): implement some retry
                stdout.println("Some trials failed to upload. Consider uploading them manually.");
            }
        } else {
            logger.fine("No upload URL was provided, so results were not uploaded.");
        }
    }
}
