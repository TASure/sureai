package ${package};

import java.util.List;

import com.sure.ai.client.AiClient;
import com.sure.ai.client.AiConfig;
import com.sure.ai.model.ChatMessage;
import com.sure.ai.model.ChatRequest;
import com.sure.ai.model.ChatResponse;
#if ($platform == "openai")
import com.sure.ai.openai.OpenAiClient;
#elseif ($platform == "azure")
import com.sure.ai.azure.AzureClient;
#elseif ($platform == "anthropic")
import com.sure.ai.anthropic.AnthropicClient;
#elseif ($platform == "gemini")
import com.sure.ai.gemini.GeminiClient;
#elseif ($platform == "deepseek")
import com.sure.ai.deepseek.DeepSeekClient;
#elseif ($platform == "qwen")
import com.sure.ai.qwen.QwenClient;
#elseif ($platform == "zhipu")
import com.sure.ai.zhipu.ZhipuClient;
#elseif ($platform == "moonshot")
import com.sure.ai.moonshot.MoonshotClient;
#elseif ($platform == "doubao")
import com.sure.ai.doubao.DoubaoClient;
#elseif ($platform == "baidu")
import com.sure.ai.baidu.BaiduClient;
#elseif ($platform == "ollama")
import com.sure.ai.ollama.OllamaClient;
#elseif ($platform == "grok")
import com.sure.ai.grok.GrokClient;
#elseif ($platform == "mistral")
import com.sure.ai.mistral.MistralClient;
#elseif ($platform == "llamacpp")
import com.sure.ai.llamacpp.LlamaCppClient;
#elseif ($platform == "cohere")
import com.sure.ai.cohere.CohereClient;
#elseif ($platform == "bedrock")
import com.sure.ai.bedrock.BedrockClient;
#elseif ($platform == "minimax")
import com.sure.ai.minimax.MiniMaxClient;
#elseif ($platform == "stepfun")
import com.sure.ai.stepfun.StepFunClient;
#elseif ($platform == "baichuan")
import com.sure.ai.baichuan.BaichuanClient;
#elseif ($platform == "lingyi")
import com.sure.ai.lingyi.LingyiClient;
#elseif ($platform == "siliconflow")
import com.sure.ai.siliconflow.SiliconFlowClient;
#elseif ($platform == "hunyuan")
import com.sure.ai.hunyuan.HunyuanClient;
#elseif ($platform == "spark")
import com.sure.ai.spark.SparkClient;
#end

/**
 * sureai Hello World：用单个平台客户端发一次对话并打印回复。
 *
 * <p>由 sure-ai-archetype 生成，平台由 -Dplatform 指定（默认 openai）。</p>
 */
public final class Main {

	private Main() {
	}

	/**
	 * 入口方法。
	 *
	 * @param args 命令行参数（未使用）
	 */
	public static void main(String[] args) {
		String apiKey = System.getenv("SURE_AI_${platform.toUpperCase()}_API_KEY");
		if (apiKey == null || apiKey.isBlank()) {
			System.out.println("请先设置环境变量 SURE_AI_${platform.toUpperCase()}_API_KEY，再运行本程序。");
			return;
		}

		AiConfig config = AiConfig.builder().apiKey(apiKey).build();
		AiClient client;
		String model;
#if ($platform == "openai")
		client = new OpenAiClient(config);
		model = "gpt-4o-mini";
#elseif ($platform == "azure")
		client = new AzureClient(config);
		model = "gpt-4o-mini";
#elseif ($platform == "anthropic")
		client = new AnthropicClient(config);
		model = "claude-3-5-sonnet-latest";
#elseif ($platform == "gemini")
		client = new GeminiClient(config);
		model = "gemini-1.5-flash";
#elseif ($platform == "deepseek")
		client = new DeepSeekClient(config);
		model = "deepseek-chat";
#elseif ($platform == "qwen")
		client = new QwenClient(config);
		model = "qwen-plus";
#elseif ($platform == "zhipu")
		client = new ZhipuClient(config);
		model = "glm-4-flash";
#elseif ($platform == "moonshot")
		client = new MoonshotClient(config);
		model = "moonshot-v1-8k";
#elseif ($platform == "doubao")
		client = new DoubaoClient(config);
		model = "doubao-pro-4k";
#elseif ($platform == "baidu")
		client = new BaiduClient(config);
		model = "ernie-speed-8k";
#elseif ($platform == "ollama")
		client = new OllamaClient(config);
		model = "llama3.1";
#elseif ($platform == "grok")
		client = new GrokClient(config);
		model = "grok-2-latest";
#elseif ($platform == "mistral")
		client = new MistralClient(config);
		model = "mistral-small-latest";
#elseif ($platform == "llamacpp")
		client = new LlamaCppClient(config);
		model = "default";
#elseif ($platform == "cohere")
		client = new CohereClient(config);
		model = "command-r-plus";
#elseif ($platform == "bedrock")
		client = new BedrockClient(config);
		model = "anthropic.claude-3-5-sonnet-20241022-v2:0";
#elseif ($platform == "minimax")
		client = new MiniMaxClient(config);
		model = "abab6.5s-chat";
#elseif ($platform == "stepfun")
		client = new StepFunClient(config);
		model = "step-1-8k";
#elseif ($platform == "baichuan")
		client = new BaichuanClient(config);
		model = "Baichuan4";
#elseif ($platform == "lingyi")
		client = new LingyiClient(config);
		model = "yi-lightning";
#elseif ($platform == "siliconflow")
		client = new SiliconFlowClient(config);
		model = "Qwen/Qwen2.5-7B-Instruct";
#elseif ($platform == "hunyuan")
		client = new HunyuanClient(config);
		model = "hunyuan-turbo";
#elseif ($platform == "spark")
		client = new SparkClient(config);
		model = "generalv3.5";
#else
		throw new IllegalStateException("不支持的平台: ${platform}，支持列表见 README.md");
#end

		try {
			ChatRequest req = ChatRequest.builder()
				.model(model)
				.messages(List.of(ChatMessage.user("用一句话介绍你自己。")))
				.build();
			ChatResponse resp = client.chat(req);
			System.out.println("${platform} 回复：" + resp.firstText());
		} finally {
			client.close();
		}
	}
}
