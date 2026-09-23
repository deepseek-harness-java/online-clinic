package cn.xiaofuge.clinic.app;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ClinicController {

    private final ClinicStore store;

    public ClinicController(ClinicStore store) {
        this.store = store;
    }

    @GetMapping("/departments")
    public Map<String, Object> departments() {
        return Map.of("code", 0, "data", store.departments);
    }

    @GetMapping("/doctors")
    public Map<String, Object> doctors(@RequestParam(required = false) String department) {
        List<Doctor> out = store.doctors.stream()
                .filter(d -> department == null || department.isBlank() || d.department.equals(department.trim()))
                .toList();
        return Map.of("code", 0, "data", out);
    }

    /** 导诊推荐：症状 → 科室 + 医生（只导诊，不诊断） */
    @GetMapping("/recommend")
    public ResponseEntity<Map<String, Object>> recommend(@RequestParam String symptom) {
        return ResponseEntity.ok(Map.of("code", 0, "disclaimer", "导诊结果仅供参考，不构成诊断意见", "data", store.recommend(symptom)));
    }

    @GetMapping("/schedule")
    public ResponseEntity<Map<String, Object>> schedule(@RequestParam(required = false) String department,
                                                        @RequestParam(required = false) String doctorId) {
        return ResponseEntity.ok(Map.of("code", 0, "data", store.schedule(department, doctorId)));
    }

    /** 创建预约（写操作） */
    @PostMapping("/bookings")
    public ResponseEntity<Map<String, Object>> book(@RequestBody Map<String, String> body) {
        try {
            ClinicStore.Booking b = store.book(body.getOrDefault("patient", ""), body.getOrDefault("doctorId", ""),
                    body.getOrDefault("date", ""), body.getOrDefault("slot", ""));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("bookingId", b.id);
            data.put("patient", b.patient);
            data.put("doctorName", b.doctorName);
            data.put("department", b.department);
            data.put("date", b.date);
            data.put("slot", b.slot);
            data.put("status", b.status);
            return ResponseEntity.ok(Map.of("code", 0, "data", data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", e.getMessage()));
        }
    }

    @GetMapping("/bookings")
    public ResponseEntity<Map<String, Object>> bookings(@RequestParam String patient) {
        return ResponseEntity.ok(Map.of("code", 0, "data", store.bookingsOf(patient)));
    }

    @PostMapping("/bookings/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable long id, @RequestBody Map<String, String> body) {
        try {
            ClinicStore.Booking b = store.cancel(body.getOrDefault("patient", ""), id);
            return ResponseEntity.ok(Map.of("code", 0, "data", Map.of("bookingId", b.id, "status", b.status)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", e.getMessage()));
        }
    }

    @GetMapping("/records")
    public ResponseEntity<Map<String, Object>> record(@RequestParam String patient) {
        try {
            return ResponseEntity.ok(Map.of("code", 0, "data", store.recordOf(patient)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("code", 1, "message", e.getMessage()));
        }
    }
}
