package cn.xiaofuge.clinic.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 康和门诊导诊插件：把 clinic-app 的 REST API 注册为 DSH Agent 工具。
 * 插件不直连数据，全部通过 HTTP 调业务应用，守住安全边界。
 * 安全红线：只做导诊与信息查询，不做诊断。
 */
public class ClinicPlugin extends AbstractHarnessPlugin {

    public static final String PLUGIN_ID = "clinic-guide-assistant";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public ClinicPlugin() { super(PLUGIN_ID); }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new RecommendDepartmentTool(),
                new DoctorScheduleTool(),
                new CreateBookingTool(),
                new BookingQueryTool(),
                new CancelBookingTool(),
                new MedicalSummaryTool());
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("clinic-guide-capabilities", 20, """
                ## 康和门诊导诊助手
                - 你是门诊导诊员，不是医生。全程只做：推荐就诊科室、查排班、约号、查预约、做病历摘要。
                - 安全红线：禁止输出任何诊断结论、疾病名称判断、用药建议；可以说"建议挂XX科由医生当面评估"，必须提醒"本服务不构成诊断意见"。
                - 用户描述症状 → recommend_department（把症状原文传给 symptom 参数）
                - 用户问某科室/某医生什么时候出诊、有什么号 → doctor_schedule
                - 用户想挂号 → 先复述：医生、日期、时段、费用，确认后再调 create_booking（写操作，必须要求患者姓名）
                - 用户问"我的预约/约了什么" → booking_query（需要患者姓名）
                - 用户要取消预约 → 先复述预约单号与医生时段，确认后调 cancel_booking（写操作）
                - 用户问"我的病历/过敏史" → medical_summary（需要患者姓名；已建档：小张、李阿姨）
                - 回答里日期、时段、余号、费用必须来自工具返回，禁止编造
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__")) {
                return PluginHookResult.context("audit: clinic tool call.");
            }
            return null;
        });
    }

    // ---- HTTP 辅助（带超时与异常兜底） ----

    private String get(String pathWithQuery, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + pathWithQuery)).GET().build());
    }

    private String post(String path, String jsonBody, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("appBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("CLINIC_APP_BASE_URL", "http://127.0.0.1:18085")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return failJson(resp.statusCode(), resp.body());
            return resp.body();
        } catch (Exception e) {
            return failJson(0, e.getMessage());
        }
    }

    private String failJson(int status, String message) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + json(message) + "\"}";
    }

    private String json(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String appendParam(String path, String name, String value) {
        if (value == null || value.isBlank()) return path;
        return path + (path.contains("?") ? "&" : "?") + name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ---- 工具定义 ----

    private class RecommendDepartmentTool extends AbstractTool {
        @Override public String name() { return "recommend_department"; }
        @Override public String description() {
            return "按症状描述推荐就诊科室与该科室医生（只导诊，不诊断）。"
                    + "何时必须调用：用户描述了身体不适/症状，想知道挂什么科。symptom 传症状原文。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("symptom", stringSchema("患者症状描述原文，如 咳嗽三天伴有低烧"))
                    .required("symptom")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get(appendParam("/api/recommend", "symptom", str(args, "symptom")), args));
        }
    }

    private class DoctorScheduleTool extends AbstractTool {
        @Override public String name() { return "doctor_schedule"; }
        @Override public String description() {
            return "查询未来 14 天出诊排班与余号，可按科室或医生过滤。"
                    + "何时必须调用：用户问某医生/科室什么时候出诊、有哪些时段可以约。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("department", stringSchema("科室名，如 呼吸内科，可选"))
                    .prop("doctorId", stringSchema("医生ID，如 d101，可选"))
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String path = appendParam(appendParam("/api/schedule", "department", str(args, "department")),
                    "doctorId", str(args, "doctorId"));
            return ok(get(path, args));
        }
    }

    private class CreateBookingTool extends AbstractTool {
        @Override public String name() { return "create_booking"; }
        @Override public String description() {
            return "创建挂号预约（写操作，调用前必须先向用户复述医生/日期/时段/费用并得到确认）。"
                    + "何时必须调用：用户明确确认要约某个医生的某个时段，且已提供患者姓名。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("patient", stringSchema("患者姓名，必填"))
                    .prop("doctorId", stringSchema("医生ID，如 d101"))
                    .prop("date", stringSchema("就诊日期 yyyy-MM-dd"))
                    .prop("slot", stringSchema("时段，如 上午 09:00-12:00"))
                    .required("patient", "doctorId", "date", "slot")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String body = "{\"patient\":\"" + json(str(args, "patient"))
                    + "\",\"doctorId\":\"" + json(str(args, "doctorId"))
                    + "\",\"date\":\"" + json(str(args, "date"))
                    + "\",\"slot\":\"" + json(str(args, "slot")) + "\"}";
            return ok(post("/api/bookings", body, args));
        }
    }

    private class BookingQueryTool extends AbstractTool {
        @Override public String name() { return "booking_query"; }
        @Override public String description() {
            return "查询患者的预约列表（含预约号、医生、时段、状态）。"
                    + "何时必须调用：用户问自己的预约/挂号记录，需要患者姓名。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("patient", stringSchema("患者姓名，必填"))
                    .required("patient")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get(appendParam("/api/bookings", "patient", str(args, "patient")), args));
        }
    }

    private class CancelBookingTool extends AbstractTool {
        @Override public String name() { return "cancel_booking"; }
        @Override public String description() {
            return "取消患者的预约（写操作，调用前必须先复述预约单号与医生时段并得到确认）。"
                    + "何时必须调用：用户明确要求取消某笔预约，且已提供患者姓名与预约号。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("patient", stringSchema("患者姓名，必填"))
                    .prop("bookingId", stringSchema("预约单号，数字，如 1001"))
                    .required("patient", "bookingId")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String id = str(args, "bookingId").trim();
            String body = "{\"patient\":\"" + json(str(args, "patient")) + "\"}";
            return ok(post("/api/bookings/" + id + "/cancel", body, args));
        }
    }

    private class MedicalSummaryTool extends AbstractTool {
        @Override public String name() { return "medical_summary"; }
        @Override public String description() {
            return "查询患者病历卡的结构化摘要（过敏史/既往史/用药/最近就诊）。仅限已建档患者（小张、李阿姨）。"
                    + "何时必须调用：用户问自己的病历、过敏史、既往病史。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("patient", stringSchema("患者姓名，必填"))
                    .required("patient")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get(appendParam("/api/records", "patient", str(args, "patient")), args));
        }
    }
}
