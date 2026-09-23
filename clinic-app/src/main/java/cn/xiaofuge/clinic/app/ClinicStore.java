package cn.xiaofuge.clinic.app;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 康和门诊预置数据（8 名医生 / 5 个科室 / 两周排班 / 预约 / 病历卡）。
 * 全部内存存储，AI 只做导诊与信息查询，不输出诊断结论（安全红线）。
 */
@Component
public class ClinicStore {

    // ---- 科室（含常见症状关键词，供导诊推荐） ----
    public record Dept(String name, String floor, String keywords) { }

    public final List<Dept> departments = List.of(
            new Dept("呼吸内科", "2楼东侧", "咳嗽,发烧,发热,感冒,流感,咽痛,嗓子疼,气短,哮喘,胸闷,咳痰"),
            new Dept("消化内科", "2楼西侧", "胃疼,腹痛,腹泻,便秘,恶心,呕吐,反酸,胃胀,消化不良,便血"),
            new Dept("心血管内科", "3楼东侧", "心悸,心慌,胸痛,高血压,头晕,心律不齐,冠心病,血压高"),
            new Dept("骨科", "1楼西侧", "腰痛,腿疼,关节疼,骨折,扭伤,颈椎,肩膀疼,膝盖,崴脚,骨质疏松"),
            new Dept("皮肤科", "4楼东侧", "皮疹,湿疹,过敏,瘙痒,痘痘,荨麻疹,脱皮,斑点,痣"));

    // ---- 医生 8 名 ----
    public final List<Doctor> doctors = new ArrayList<>();

    // ---- 排班：doctorId -> (date -> list of {slot, capacity, booked}) ----
    public final Map<String, Map<String, List<Map<String, Object>>>> schedules = new LinkedHashMap<>();

    // ---- 预约 ----
    public static class Booking {
        public long id;
        public String patient;
        public String doctorId;
        public String doctorName;
        public String department;
        public String date;
        public String slot;
        public String status = "已预约";
        Booking(long id, String patient, Doctor d, String date, String slot) {
            this.id = id; this.patient = patient; this.doctorId = d.id;
            this.doctorName = d.name; this.department = d.department;
            this.date = date; this.slot = slot;
        }
    }
    public final List<Booking> bookings = new CopyOnWriteArrayList<>();
    private final AtomicLong bookingSeq = new AtomicLong(1000);

    // ---- 病历卡 ----
    public static class MedicalRecord {
        public String patient;
        public String allergies;
        public String history;
        public String medications;
        public String lastVisit;
        public MedicalRecord(String patient, String allergies, String history, String medications, String lastVisit) {
            this.patient = patient; this.allergies = allergies;
            this.history = history; this.medications = medications; this.lastVisit = lastVisit;
        }
    }
    public final List<MedicalRecord> records = new CopyOnWriteArrayList<>();

    public ClinicStore() {
        doctors.add(new Doctor("d101", "陈志远", "呼吸内科", "主任医师",
                "从医 28 年，擅长慢性咳嗽、哮喘与慢阻肺的规范化诊疗", 50));
        doctors.add(new Doctor("d102", "林晚晴", "呼吸内科", "主治医师",
                "擅长呼吸道感染、流感诊治，专注儿童与成人感冒发热", 30));
        doctors.add(new Doctor("d201", "周明礼", "消化内科", "副主任医师",
                "擅长胃炎、胃食管反流与功能性消化不良，消化内镜经验丰富", 40));
        doctors.add(new Doctor("d202", "苏若云", "消化内科", "主治医师",
                "擅长肠道疾病与幽门螺杆菌感染的规范治疗", 30));
        doctors.add(new Doctor("d301", "郑海峰", "心血管内科", "主任医师",
                "从医 30 年，擅长高血压、冠心病与心律失常的综合管理", 60));
        doctors.add(new Doctor("d401", "韩铁生", "骨科", "主任医师",
                "擅长颈肩腰腿痛、运动损伤与骨关节炎，骨科手术经验丰富", 50));
        doctors.add(new Doctor("d402", "方芷若", "骨科", "主治医师",
                "擅长急性扭伤、骨折复位与康复指导", 30));
        doctors.add(new Doctor("d501", "叶蔓青", "皮肤科", "副主任医师",
                "擅长湿疹、荨麻疹与痤疮的个体化治疗", 40));

        // 排班：未来 14 天，每人每周出诊 3 天，上午/下午两个时段
        String[] dates = next14Dates();
        for (Doctor d : doctors) {
            Map<String, List<Map<String, Object>>> byDate = new LinkedHashMap<>();
            int dayIdx = Integer.parseInt(d.id.substring(1)); // 稳定的出诊日偏移
            for (int i = 0; i < dates.length; i++) {
                if ((i + dayIdx) % 3 != 0) continue; // 每 3 天出诊一次
                List<Map<String, Object>> slots = new ArrayList<>();
                int capBase = d.title.contains("主任") ? 6 : 10;
                slots.add(slot("上午 09:00-12:00", capBase, (i * 3 + dayIdx) % (capBase / 2 + 1)));
                slots.add(slot("下午 14:00-17:00", capBase, (i + dayIdx * 2) % (capBase / 2 + 1)));
                byDate.put(dates[i], slots);
            }
            schedules.put(d.id, byDate);
        }

        records.add(new MedicalRecord("小张", "青霉素过敏",
                "2025 年确诊慢性胃炎，规律服药后好转；2026 年初流感一次",
                "奥美拉唑（胃炎发作时）", "2026-08-12 消化内科 周明礼"));
        records.add(new MedicalRecord("李阿姨", "无已知过敏",
                "高血压病史 8 年，规律服用氨氯地平；腰椎间盘突出（保守治疗中）",
                "氨氯地平 5mg 每日一次", "2026-09-05 心血管内科 郑海峰"));
    }

    private static Map<String, Object> slot(String name, int capacity, int booked) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("slot", name);
        m.put("capacity", capacity);
        m.put("booked", booked);
        m.put("left", Math.max(0, capacity - booked));
        return m;
    }

    private static String[] next14Dates() {
        String[] dates = new String[14];
        java.time.LocalDate d = java.time.LocalDate.now();
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
        for (int i = 0; i < 14; i++) dates[i] = d.plusDays(i).format(f);
        return dates;
    }

    // ---- 查询逻辑 ----

    /** 症状关键词 → 推荐科室与医生（只导诊，不做诊断） */
    public List<Map<String, Object>> recommend(String symptom) {
        String s = symptom == null ? "" : symptom;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Dept dept : departments) {
            int hit = 0;
            List<String> matched = new ArrayList<>();
            for (String kw : dept.keywords.split(",")) {
                if (s.contains(kw.trim())) { hit++; matched.add(kw.trim()); }
            }
            if (hit == 0) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("department", dept.name);
            item.put("floor", dept.floor);
            item.put("matchedKeywords", matched);
            List<Map<String, Object>> docs = new ArrayList<>();
            for (Doctor d : doctors) {
                if (!d.department.equals(dept.name)) continue;
                Map<String, Object> dm = new LinkedHashMap<>();
                dm.put("doctorId", d.id);
                dm.put("name", d.name);
                dm.put("title", d.title);
                dm.put("intro", d.intro);
                dm.put("fee", d.fee);
                docs.add(dm);
            }
            item.put("doctors", docs);
            out.add(item);
        }
        return out;
    }

    /** 科室排班（可按医生过滤），只返回有余号的时段 */
    public List<Map<String, Object>> schedule(String department, String doctorId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Doctor d : doctors) {
            if (department != null && !department.isBlank() && !d.department.equals(department.trim())) continue;
            if (doctorId != null && !doctorId.isBlank() && !d.id.equals(doctorId.trim())) continue;
            Map<String, List<Map<String, Object>>> byDate = schedules.get(d.id);
            if (byDate == null) continue;
            for (Map.Entry<String, List<Map<String, Object>>> e : byDate.entrySet()) {
                for (Map<String, Object> s : e.getValue()) {
                    if ((int) s.get("left") <= 0) continue;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("doctorId", d.id);
                    row.put("doctorName", d.name);
                    row.put("title", d.title);
                    row.put("department", d.department);
                    row.put("fee", d.fee);
                    row.put("date", e.getKey());
                    row.put("slot", s.get("slot"));
                    row.put("left", s.get("left"));
                    out.add(row);
                }
            }
        }
        return out;
    }

    /** 创建预约（写操作，agent 必须先复述确认） */
    public synchronized Booking book(String patient, String doctorId, String date, String slotName) {
        Doctor d = doctors.stream().filter(x -> x.id.equals(doctorId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("医生不存在: " + doctorId));
        Map<String, List<Map<String, Object>>> byDate = schedules.get(d.id);
        if (byDate == null || !byDate.containsKey(date))
            throw new IllegalArgumentException("该医生在 " + date + " 没有排班");
        Map<String, Object> target = byDate.get(date).stream()
                .filter(s -> s.get("slot").equals(slotName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("时段不存在: " + slotName + "（可选：" +
                        byDate.get(date).stream().map(s -> s.get("slot")).reduce((a, b) -> a + " / " + b).orElse("") + "）"));
        if ((int) target.get("left") <= 0)
            throw new IllegalArgumentException("该时段已约满（剩余 0）");
        boolean dup = bookings.stream().anyMatch(b -> b.patient.equals(patient)
                && b.doctorId.equals(doctorId) && b.date.equals(date) && b.slot.equals(slotName)
                && !"已取消".equals(b.status));
        if (dup) throw new IllegalArgumentException("该患者已预约同一时段，请勿重复预约");
        target.put("booked", (int) target.get("booked") + 1);
        target.put("left", Math.max(0, (int) target.get("left") - 1));
        Booking b = new Booking(bookingSeq.getAndIncrement(), patient, d, date, slotName);
        bookings.add(b);
        return b;
    }

    /** 查患者预约 */
    public List<Booking> bookingsOf(String patient) {
        List<Booking> out = new ArrayList<>();
        for (Booking b : bookings) {
            if (b.patient.equals(patient)) out.add(b);
        }
        return out;
    }

    /** 取消预约（写操作） */
    public synchronized Booking cancel(String patient, long bookingId) {
        Booking b = bookings.stream()
                .filter(x -> x.id == bookingId && x.patient.equals(patient))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("预约不存在: #" + bookingId));
        if ("已取消".equals(b.status)) throw new IllegalArgumentException("该预约已是取消状态");
        b.status = "已取消";
        // 回补余号
        Map<String, List<Map<String, Object>>> byDate = schedules.get(b.doctorId);
        if (byDate != null && byDate.containsKey(b.date)) {
            for (Map<String, Object> s : byDate.get(b.date)) {
                if (s.get("slot").equals(b.slot)) {
                    s.put("booked", Math.max(0, (int) s.get("booked") - 1));
                    s.put("left", (int) s.get("left") + 1);
                }
            }
        }
        return b;
    }

    /** 病历卡结构化摘要 */
    public MedicalRecord recordOf(String patient) {
        return records.stream().filter(r -> r.patient.equals(patient)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未建档患者: " + patient + "（可建档患者：小张 / 李阿姨）"));
    }
}
