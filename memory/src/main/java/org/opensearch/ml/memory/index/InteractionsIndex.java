/*
 * Copyright 2023 Aryn
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opensearch.ml.memory.index;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedList;
import java.util.List;

import org.opensearch.OpenSearchSecurityException;
import org.opensearch.OpenSearchWrapperException;
import org.opensearch.ResourceAlreadyExistsException;
import org.opensearch.action.admin.indices.create.CreateIndexRequest;
import org.opensearch.action.admin.indices.create.CreateIndexResponse;
import org.opensearch.action.bulk.BulkRequest;
import org.opensearch.action.delete.DeleteRequest;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.client.Client;
import org.opensearch.client.Requests;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.util.concurrent.ThreadContext;
import org.opensearch.commons.ConfigConstants;
import org.opensearch.commons.authuser.User;
import org.opensearch.core.action.ActionListener;
import org.opensearch.core.rest.RestStatus;
import org.opensearch.index.query.TermQueryBuilder;
import org.opensearch.ml.common.conversation.ActionConstants;
import org.opensearch.ml.common.conversation.ConversationalIndexConstants;
import org.opensearch.ml.common.conversation.Interaction;
import org.opensearch.search.SearchHit;
import org.opensearch.search.sort.SortOrder;

import com.google.common.annotations.VisibleForTesting;

import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Class for handling the interactions index
 */
@Log4j2
@AllArgsConstructor
public class InteractionsIndex {

    private Client client;
    private ClusterService clusterService;
    private ConversationMetaIndex conversationMetaIndex;
    private final String indexName = ConversationalIndexConstants.INTERACTIONS_INDEX_NAME;
    // How big the steps should be when gathering *ALL* interactions in a conversation
    private final int resultsAtATime = 300;

    /**
     * 'PUT's the index in opensearch if it's not there already
     * @param listener gets whether the index needed to be initialized. Throws error if it fails to init
     */
    public void initInteractionsIndexIfAbsent(ActionListener<Boolean> listener) {
        if (!clusterService.state().metadata().hasIndex(indexName)) {
            log.debug("No interactions index found. Adding it");
            CreateIndexRequest request = Requests.createIndexRequest(indexName).mapping(ConversationalIndexConstants.INTERACTIONS_MAPPINGS);
            try (ThreadContext.StoredContext threadContext = client.threadPool().getThreadContext().stashContext()) {
                ActionListener<Boolean> internalListener = ActionListener.runBefore(listener, () -> threadContext.restore());
                ActionListener<CreateIndexResponse> al = ActionListener.wrap(r -> {
                    if (r.equals(new CreateIndexResponse(true, true, indexName))) {
                        log.info("created index [" + indexName + "]");
                        internalListener.onResponse(true);
                    } else {
                        internalListener.onResponse(false);
                    }
                }, e -> {
                    if (e instanceof ResourceAlreadyExistsException
                        || (e instanceof OpenSearchWrapperException && e.getCause() instanceof ResourceAlreadyExistsException)) {
                        internalListener.onResponse(true);
                    } else {
                        log.error("Failed to create index [" + indexName + "]", e);
                        internalListener.onFailure(e);
                    }
                });
                client.admin().indices().create(request, al);
            } catch (Exception e) {
                if (e instanceof ResourceAlreadyExistsException
                    || (e instanceof OpenSearchWrapperException && e.getCause() instanceof ResourceAlreadyExistsException)) {
                    listener.onResponse(true);
                } else {
                    log.error("Failed to create index [" + indexName + "]", e);
                    listener.onFailure(e);
                }
            }
        } else {
            listener.onResponse(true);
        }
    }

    /**
     * Add an interaction to this index. Return the ID of the newly created interaction
     * @param conversationId The id of the conversation this interaction belongs to
     * @param input the user (human) input into this interaction
     * @param promptTemplate the prompt template used for this interaction
     * @param response the GenAI response for this interaction
     * @param origin the origin of the response for this interaction
     * @param additionalInfo additional information used for constructing the LLM prompt
     * @param timestamp when this interaction happened
     * @param listener gets the id of the newly created interaction record
     */
    public void createInteraction(
        String conversationId,
        String input,
        String promptTemplate,
        String response,
        String origin,
        String additionalInfo,
        Instant timestamp,
        ActionListener<String> listener
    ) {
        initInteractionsIndexIfAbsent(ActionListener.wrap(indexExists -> {
            String userstr = client
                .threadPool()
                .getThreadContext()
                .getTransient(ConfigConstants.OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT);
            String user = User.parse(userstr) == null ? ActionConstants.DEFAULT_USERNAME_FOR_ERRORS : User.parse(userstr).getName();
            if (indexExists) {
                this.conversationMetaIndex.checkAccess(conversationId, ActionListener.wrap(access -> {
                    if (access) {
                        IndexRequest request = Requests
                            .indexRequest(indexName)
                            .source(
                                ConversationalIndexConstants.INTERACTIONS_ORIGIN_FIELD,
                                origin,
                                ConversationalIndexConstants.INTERACTIONS_CONVERSATION_ID_FIELD,
                                conversationId,
                                ConversationalIndexConstants.INTERACTIONS_INPUT_FIELD,
                                input,
                                ConversationalIndexConstants.INTERACTIONS_PROMPT_TEMPLATE_FIELD,
                                promptTemplate,
                                ConversationalIndexConstants.INTERACTIONS_RESPONSE_FIELD,
                                response,
                                ConversationalIndexConstants.INTERACTIONS_ADDITIONAL_INFO_FIELD,
                                additionalInfo,
                                ConversationalIndexConstants.INTERACTIONS_CREATE_TIME_FIELD,
                                timestamp
                            );
                        try (ThreadContext.StoredContext threadContext = client.threadPool().getThreadContext().stashContext()) {
                            ActionListener<String> internalListener = ActionListener.runBefore(listener, () -> threadContext.restore());
                            ActionListener<IndexResponse> al = ActionListener.wrap(resp -> {
                                if (resp.status() == RestStatus.CREATED) {
                                    internalListener.onResponse(resp.getId());
                                } else {
                                    internalListener.onFailure(new IOException("Failed to create interaction"));
                                }
                            }, e -> { internalListener.onFailure(e); });
                            client.index(request, al);
                        } catch (Exception e) {
                            listener.onFailure(e);
                        }
                    } else {
                        throw new OpenSearchSecurityException("User [" + user + "] does not have access to conversation " + conversationId);
                    }
                }, e -> { listener.onFailure(e); }));
            } else {
                listener.onFailure(new IOException("no index to add conversation to"));
            }
        }, e -> { listener.onFailure(e); }));
    }

    /**
     * Add an interaction to this index, timestamped now. Return the id of the newly created interaction
     * @param conversationId The id of the converation this interaction belongs to
     * @param input the user (human) input into this interaction
     * @param promptTemplate the prompt template used for this interaction
     * @param response the GenAI response for this interaction
     * @param origin the name of the GenAI agent this interaction belongs to
     * @param additionalInfo additional information used to construct the LLM prompt
     * @param listener gets the id of the newly created interaction record
     */
    public void createInteraction(
        String conversationId,
        String input,
        String promptTemplate,
        String response,
        String origin,
        String additionalInfo,
        ActionListener<String> listener
    ) {
        createInteraction(conversationId, input, promptTemplate, response, origin, additionalInfo, Instant.now(), listener);
    }

    /**
     * Gets a list of interactions belonging to a conversation
     * @param conversationId the conversation to read from
     * @param from where to start in the reading
     * @param maxResults how many interactions to return
     * @param listener gets the list, sorted by recency, of interactions
     */
    public void getInteractions(String conversationId, int from, int maxResults, ActionListener<List<Interaction>> listener) {
        if (!clusterService.state().metadata().hasIndex(indexName)) {
            listener.onResponse(List.of());
            return;
        }
        ActionListener<Boolean> accessListener = ActionListener.wrap(access -> {
            if (access) {
                innerGetInteractions(conversationId, from, maxResults, listener);
            } else {
                String userstr = client
                    .threadPool()
                    .getThreadContext()
                    .getTransient(ConfigConstants.OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT);
                String user = User.parse(userstr) == null ? ActionConstants.DEFAULT_USERNAME_FOR_ERRORS : User.parse(userstr).getName();
                throw new OpenSearchSecurityException("User [" + user + "] does not have access to conversation " + conversationId);
            }
        }, e -> { listener.onFailure(e); });
        conversationMetaIndex.checkAccess(conversationId, accessListener);
    }

    @VisibleForTesting
    void innerGetInteractions(String conversationId, int from, int maxResults, ActionListener<List<Interaction>> listener) {
        SearchRequest request = Requests.searchRequest(indexName);
        TermQueryBuilder builder = new TermQueryBuilder(ConversationalIndexConstants.INTERACTIONS_CONVERSATION_ID_FIELD, conversationId);
        request.source().query(builder);
        request.source().from(from).size(maxResults);
        request.source().sort(ConversationalIndexConstants.INTERACTIONS_CREATE_TIME_FIELD, SortOrder.DESC);
        try (ThreadContext.StoredContext threadContext = client.threadPool().getThreadContext().stashContext()) {
            ActionListener<List<Interaction>> internalListener = ActionListener.runBefore(listener, () -> threadContext.restore());
            ActionListener<SearchResponse> al = ActionListener.wrap(response -> {
                List<Interaction> result = new LinkedList<Interaction>();
                for (SearchHit hit : response.getHits()) {
                    result.add(Interaction.fromSearchHit(hit));
                }
                internalListener.onResponse(result);
            }, e -> { internalListener.onFailure(e); });
            client
                .admin()
                .indices()
                .refresh(Requests.refreshRequest(indexName), ActionListener.wrap(r -> { client.search(request, al); }, e -> {
                    internalListener.onFailure(e);
                }));
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    /**
     * Gets all of the interactions in a conversation, regardless of conversation size
     * @param conversationId conversation to get all interactions of
     * @param maxResults how many interactions to get per search query
     * @param listener receives the list of all interactions in the conversation
     */
    @VisibleForTesting
    void getAllInteractions(String conversationId, int maxResults, ActionListener<List<Interaction>> listener) {
        ActionListener<List<Interaction>> al = nextGetListener(conversationId, 0, maxResults, listener, new LinkedList<>());
        innerGetInteractions(conversationId, 0, maxResults, al);
    }

    /**
     * Recursively builds the list of interactions for getAllInteractions by returning an
     * ActionListener for handling the next search query
     * @param conversationId conversation to get interactions from
     * @param from where to start in this step
     * @param maxResults how many to get in this step
     * @param mainListener listener for the final result
     * @param result partially built list of interactions
     * @return an ActionListener to handle the next search query
     */
    @VisibleForTesting
    ActionListener<List<Interaction>> nextGetListener(
        String conversationId,
        int from,
        int maxResults,
        ActionListener<List<Interaction>> mainListener,
        List<Interaction> result
    ) {
        if (maxResults < 1) {
            mainListener.onFailure(new IllegalArgumentException("maxResults must be positive"));
            return null;
        }
        return ActionListener.wrap(interactions -> {
            result.addAll(interactions);
            if (interactions.size() < maxResults) {
                mainListener.onResponse(result);
            } else {
                ActionListener<List<Interaction>> al = nextGetListener(conversationId, from + maxResults, maxResults, mainListener, result);
                innerGetInteractions(conversationId, from + maxResults, maxResults, al);
            }
        }, e -> { mainListener.onFailure(e); });
    }

    /**
     * Deletes all interactions associated with a conversationId
     * Note this uses a bulk delete request (and tries to delete an entire conversation) so it may be heavyweight
     * @param conversationId the id of the conversation to delete from
     * @param listener gets whether the deletion was successful
     */
    public void deleteConversation(String conversationId, ActionListener<Boolean> listener) {
        if (!clusterService.state().metadata().hasIndex(indexName)) {
            listener.onResponse(true);
            return;
        }
        String userstr = client.threadPool().getThreadContext().getTransient(ConfigConstants.OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT);
        String user = User.parse(userstr) == null ? ActionConstants.DEFAULT_USERNAME_FOR_ERRORS : User.parse(userstr).getName();
        try (ThreadContext.StoredContext threadContext = client.threadPool().getThreadContext().stashContext()) {
            ActionListener<Boolean> internalListener = ActionListener.runBefore(listener, () -> threadContext.restore());
            ActionListener<List<Interaction>> searchListener = ActionListener.wrap(interactions -> {
                BulkRequest request = Requests.bulkRequest();
                for (Interaction interaction : interactions) {
                    DeleteRequest delRequest = Requests.deleteRequest(indexName).id(interaction.getId());
                    request.add(delRequest);
                }
                client
                    .bulk(request, ActionListener.wrap(bulkResponse -> { internalListener.onResponse(!bulkResponse.hasFailures()); }, e -> {
                        internalListener.onFailure(e);
                    }));
            }, e -> { internalListener.onFailure(e); });
            ActionListener<Boolean> accessListener = ActionListener.wrap(access -> {
                if (access) {
                    getAllInteractions(conversationId, resultsAtATime, searchListener);
                } else {
                    throw new OpenSearchSecurityException("User [" + user + "] does not have access to conversation " + conversationId);
                }
            }, e -> { listener.onFailure(e); });
            conversationMetaIndex.checkAccess(conversationId, accessListener);
        } catch (Exception e) {
            log.error("Failure while deleting interactions associated with conversation id=" + conversationId, e);
            listener.onFailure(e);
        }
    }

}
