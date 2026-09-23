package cn.xiaofuge.clinic.app;

/** 医生。 */
public class Doctor {
    public String id;
    public String name;
    /** 科室名，如 呼吸内科 */
    public String department;
    /** 主任/副主任/主治/住院 */
    public String title;
    public String intro;
    /** 挂号费（元） */
    public double fee;

    public Doctor() { }

    public Doctor(String id, String name, String department, String title, String intro, double fee) {
        this.id = id; this.name = name; this.department = department;
        this.title = title; this.intro = intro; this.fee = fee;
    }
}
