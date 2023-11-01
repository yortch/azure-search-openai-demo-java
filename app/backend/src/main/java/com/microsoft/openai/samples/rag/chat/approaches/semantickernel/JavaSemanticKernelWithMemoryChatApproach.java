package com.microsoft.openai.samples.rag.chat.approaches.semantickernel;

import com.azure.ai.openai.OpenAIAsyncClient;
import com.azure.core.credential.TokenCredential;
import com.azure.search.documents.SearchAsyncClient;
import com.azure.search.documents.SearchDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.openai.samples.rag.approaches.ContentSource;
import com.microsoft.openai.samples.rag.approaches.RAGApproach;
import com.microsoft.openai.samples.rag.approaches.RAGOptions;
import com.microsoft.openai.samples.rag.approaches.RAGResponse;
import com.microsoft.openai.samples.rag.ask.approaches.semantickernel.memory.CustomAzureCognitiveSearchMemoryStore;
import com.microsoft.openai.samples.rag.common.ChatGPTConversation;
import com.microsoft.openai.samples.rag.common.ChatGPTUtils;
import com.microsoft.openai.samples.rag.controller.ChatResponse;
import com.microsoft.semantickernel.Kernel;
import com.microsoft.semantickernel.SKBuilders;
import com.microsoft.semantickernel.ai.embeddings.Embedding;
import com.microsoft.semantickernel.memory.MemoryQueryResult;
import com.microsoft.semantickernel.memory.MemoryRecord;
import com.microsoft.semantickernel.orchestration.SKContext;
import com.microsoft.semantickernel.orchestration.SKFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Accomplish the same task as in the PlainJavaAskApproach approach but using Semantic Kernel framework:
 * 1. Memory abstraction is used for vector search capability. It uses Azure Cognitive Search as memory store.
 * 2. Semantic function has been defined to ask question using sources from memory search results
 */
@Component
public class JavaSemanticKernelWithMemoryChatApproach implements RAGApproach<ChatGPTConversation, RAGResponse> {
    private static final Logger LOGGER = LoggerFactory.getLogger(JavaSemanticKernelWithMemoryChatApproach.class);
    private final TokenCredential tokenCredential;
    private final OpenAIAsyncClient openAIAsyncClient;

    private final SearchAsyncClient searchAsyncClient;

    private final ObjectMapper objectMapper;

    private final String EMBEDDING_FIELD_NAME = "embedding";

    @Value("${cognitive.search.service}")
    String searchServiceName;
    @Value("${cognitive.search.index}")
    String indexName;
    @Value("${openai.chatgpt.deployment}")
    private String gptChatDeploymentModelId;

    @Value("${openai.embedding.deployment}")
    private String embeddingDeploymentModelId;
    public JavaSemanticKernelWithMemoryChatApproach(TokenCredential tokenCredential, OpenAIAsyncClient openAIAsyncClient, SearchAsyncClient searchAsyncClient, ObjectMapper objectMapper) {
        this.tokenCredential = tokenCredential;
        this.openAIAsyncClient = openAIAsyncClient;
        this.searchAsyncClient = searchAsyncClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public RAGResponse run(ChatGPTConversation questionOrConversation, RAGOptions options) {
        String question = ChatGPTUtils.getLastUserQuestion(questionOrConversation.getMessages());

        // STEP 1: Build semantic kernel with Azure Cognitive Search as memory store. AnswerQuestion skill is imported from resources.
        Kernel semanticKernel = buildSemanticKernel(options);

        // STEP 2: Retrieve relevant documents using keywords extracted from the chat history
        String conversation = ChatGPTUtils.formatAsChatML(questionOrConversation.toOpenAIChatMessages());
        List<MemoryQueryResult> sourcesResult = getSourcesFromConversation(conversation, semanticKernel, options);

        LOGGER.info("Total {} sources found in cognitive vector store for search query[{}]", sourcesResult.size(), question);

        String sources = buildSourcesText(sourcesResult);
        List<ContentSource> sourcesList = buildSources(sourcesResult);

        // STEP 3: Generate a contextual and content specific answer using the search results and chat history
        SKFunction answerConversation = semanticKernel.getFunction("RAG", "AnswerConversation");
        SKContext skcontext = SKBuilders.context().build()
                .setVariable("sources", sources)
                .setVariable("conversation", conversation)
                .setVariable("suggestions", String.valueOf(options.isSuggestFollowupQuestions()))
                .setVariable("input",  question);

        Mono<SKContext> reply = answerConversation.invokeAsync(skcontext);

        return new RAGResponse.Builder()
                .prompt("placeholders for prompt")
                .answer(reply.block().getResult())
                .sources(sourcesList)
                .sourcesAsText(sources)
                .question(question)
                .build();
    }

    @Override
    public void runStreaming(ChatGPTConversation questionOrConversation, RAGOptions options, OutputStream outputStream) {
        String question = ChatGPTUtils.getLastUserQuestion(questionOrConversation.getMessages());

        // STEP 1: Build semantic kernel with Azure Cognitive Search as memory store. AnswerQuestion skill is imported from resources.
        Kernel semanticKernel = buildSemanticKernel(options);

        // STEP 2: Retrieve relevant documents using keywords extracted from the chat history
        String conversation = ChatGPTUtils.formatAsChatML(questionOrConversation.toOpenAIChatMessages());
        List<MemoryQueryResult> sourcesResult = getSourcesFromConversation(conversation, semanticKernel, options);

        LOGGER.info("Total {} sources found in cognitive vector store for search query[{}]", sourcesResult.size(), question);

        String sources = buildSourcesText(sourcesResult);
        List<ContentSource> sourcesList = buildSources(sourcesResult);

        // STEP 3: Generate a contextual and content specific answer using the search results and chat history
        SKFunction answerConversation = semanticKernel.getFunction("RAG", "AnswerConversation");
        SKContext skcontext = SKBuilders.context().build()
                .setVariable("sources", sources)
                .setVariable("conversation", conversation)
                .setVariable("suggestions", String.valueOf(options.isSuggestFollowupQuestions()))
                .setVariable("input",  question);

        SKContext reply = (SKContext) answerConversation.invokeAsync(skcontext).block();

        RAGResponse ragResponse =
                new RAGResponse.Builder()
                        .question(
                                ChatGPTUtils.getLastUserQuestion(
                                        questionOrConversation.getMessages()))
                        .prompt("placeholders for prompt")
                        .answer(reply.getResult())
                        .sources(sourcesList)
                        .sourcesAsText(sources)
                        .build();

        try {
            String value = objectMapper.writeValueAsString(ChatResponse.buildChatResponse(ragResponse)) + "\n";
            outputStream.write(value.getBytes());
            outputStream.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private List<MemoryQueryResult> getSourcesFromConversation (String conversation, Kernel kernel, RAGOptions options) {
        SKFunction extractKeywords = kernel.getFunction("RAG", "ExtractKeywords");
        SKContext skcontext = SKBuilders.context().build().setVariable("conversation", conversation);

        Mono<SKContext> result = extractKeywords.invokeAsync(skcontext);
        String searchQuery = result.block().getResult();

        /**
         * Use semantic kernel built-in memory.searchAsync. It uses OpenAI to generate embeddings for the provided question.
         * Question embeddings are provided to cognitive search via search options.
         */
        List<MemoryQueryResult> memoryResult = kernel.getMemory().searchAsync(
                        indexName,
                        searchQuery,
                        options.getTop(),
                        0.5f,
                        false)
                .block();

        return memoryResult;
    }

    private List<ContentSource> buildSources(List<MemoryQueryResult> memoryResult) {
        return memoryResult
                .stream()
                .map(result -> {
                    return new ContentSource(
                            result.getMetadata().getId(),
                            result.getMetadata().getText()
                    );
                })
                .collect(Collectors.toList());
    }

    private String buildSourcesText(List<MemoryQueryResult> memoryResult) {
        StringBuilder sourcesContentBuffer = new StringBuilder();
        memoryResult.stream().forEach(memory -> {
            sourcesContentBuffer.append(memory.getMetadata().getId())
                    .append(": ")
                    .append(memory.getMetadata().getText().replace("\n", ""))
                    .append("\n");
        });
        return sourcesContentBuffer.toString();
    }

    private Kernel buildSemanticKernel(RAGOptions options) {
        var kernelWithACS = SKBuilders.kernel()
                .withMemoryStorage(
                        new CustomAzureCognitiveSearchMemoryStore("https://%s.search.windows.net".formatted(searchServiceName),
                                tokenCredential,
                                this.searchAsyncClient,
                                this.EMBEDDING_FIELD_NAME,
                                buildCustomMemoryMapper()))
                .withDefaultAIService(SKBuilders.textEmbeddingGeneration()
                        .withOpenAIClient(openAIAsyncClient)
                        .withModelId(embeddingDeploymentModelId)
                        .build())
                .withDefaultAIService(SKBuilders.chatCompletion()
                        .withModelId(gptChatDeploymentModelId)
                        .withOpenAIClient(this.openAIAsyncClient)
                        .build())
                .build();

        kernelWithACS.importSkillFromResources("semantickernel/Plugins", "RAG", "AnswerConversation", null);
        kernelWithACS.importSkillFromResources("semantickernel/Plugins", "RAG", "ExtractKeywords", null);
        return kernelWithACS;
    }

    private Function<SearchDocument, MemoryRecord> buildCustomMemoryMapper() {
        return searchDocument -> {
            return MemoryRecord.localRecord(
                    (String) searchDocument.get("sourcepage"),
                    (String) searchDocument.get("content"),
                    "chunked text from original source",
                    new Embedding((List<Float>) searchDocument.get(EMBEDDING_FIELD_NAME)),
                    (String) searchDocument.get("category"),
                    (String) searchDocument.get("id"),
                    null);

        };
    }
}
