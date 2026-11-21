package com.kang.ubcm

/**
 * 真实课表夹具：2026–2027 学年第 1 学期（岐黄班），
 * 由 ~/Desktop/bucm/courses.json 生成（源：教务 bzyXskbList，2026-11-02 导出）。
 * 只保留算下一节课需要的字段。
 * 第 1 周 = 2026-09-07（周一）。
 */
object TestTimetable {

    const val FIRST_WEEK_MONDAY = "2026-09-07"

    /** 良乡作息（教务大节表推算结果，与官方导出表一致） */
    val SLOTS = listOf(
        PeriodSlot(1, 510, 555),
        PeriodSlot(2, 555, 600),
        PeriodSlot(3, 630, 675),
        PeriodSlot(4, 675, 720),
        PeriodSlot(5, 810, 855),
        PeriodSlot(6, 855, 900),
        PeriodSlot(7, 930, 975),
        PeriodSlot(8, 975, 1020),
        PeriodSlot(9, 1080, 1125),
        PeriodSlot(10, 1125, 1170),
        PeriodSlot(11, 1180, 1225),
        PeriodSlot(12, 1225, 1270),
    )

    val SESSIONS = listOf(
        CourseSession(name = "中医基础学", teacher = "王彤", location = "良东408教", dayOfWeek = 1, startPeriod = 1, endPeriod = 3, weeks = listOf(4,5,6,7,8,9,10,11,12,13,14,15)),
        CourseSession(name = "中医基础学", teacher = "王彤", location = "良东228教", dayOfWeek = 3, startPeriod = 5, endPeriod = 7, weeks = listOf(4,5,6,7,8,9,10,11,12,13,14,15)),
        CourseSession(name = "中医基础学", teacher = "王彤", location = "良东317教", dayOfWeek = 5, startPeriod = 1, endPeriod = 2, weeks = listOf(4,5,6,7,8,9,10,11,12,13)),
        CourseSession(name = "中医基础学", teacher = "王彤", location = "良东316教", dayOfWeek = 5, startPeriod = 1, endPeriod = 1, weeks = listOf(14)),
        CourseSession(name = "方药学", teacher = "陈绍红", location = "良东211教", dayOfWeek = 1, startPeriod = 1, endPeriod = 3, weeks = listOf(16,17)),
        CourseSession(name = "方药学", teacher = "陈绍红", location = "良东231教", dayOfWeek = 3, startPeriod = 1, endPeriod = 2, weeks = listOf(13,14,15,16,17)),
        CourseSession(name = "方药学", teacher = "陈绍红", location = "良东228教", dayOfWeek = 5, startPeriod = 5, endPeriod = 7, weeks = listOf(12,13,14,15,16,17)),
        CourseSession(name = "正常人体学Ⅰ", teacher = "司银楚", location = "解剖实验室1", dayOfWeek = 2, startPeriod = 1, endPeriod = 4, weeks = listOf(4,5,6,7,8,9,10,11,12,13,14,15)),
        CourseSession(name = "正常人体学Ⅰ", teacher = "李健", location = "生命探究创新实践平台（320室）", dayOfWeek = 2, startPeriod = 1, endPeriod = 4, weeks = listOf(16,17)),
        CourseSession(name = "正常人体学Ⅰ", teacher = "司银楚", location = "解剖实验室1", dayOfWeek = 4, startPeriod = 1, endPeriod = 2, weeks = listOf(4,5,6,7,8,9,10,11,12,13)),
        CourseSession(name = "古汉语基础", teacher = "李蕊", location = "良东174教", dayOfWeek = 3, startPeriod = 1, endPeriod = 2, weeks = listOf(4,5,6,7,8,9,10,11,12)),
        CourseSession(name = "古汉语基础", teacher = "杨明明", location = "良东278教", dayOfWeek = 4, startPeriod = 1, endPeriod = 2, weeks = listOf(14,15,16,17)),
        CourseSession(name = "古汉语基础", teacher = "李蕊", location = "良东166教", dayOfWeek = 5, startPeriod = 3, endPeriod = 4, weeks = listOf(8,9,10,11)),
        CourseSession(name = "高等数学", teacher = "杜飞龙", location = "良东374教", dayOfWeek = 2, startPeriod = 7, endPeriod = 8, weeks = listOf(4,5,6,7,8,9,10,11,12)),
        CourseSession(name = "高等数学", teacher = "杜飞龙", location = "良东278教", dayOfWeek = 4, startPeriod = 7, endPeriod = 8, weeks = listOf(10,11,12,13,14,15,16,17)),
        CourseSession(name = "思想政治理论课Ⅰ", teacher = "邱仁富", location = "良东174教", dayOfWeek = 3, startPeriod = 3, endPeriod = 4, weeks = listOf(4,5,6,7,8,9,10,11,12,13,14,15,16,17)),
        CourseSession(name = "思想政治理论课Ⅰ", teacher = "邱仁富", location = "良西302教", dayOfWeek = 1, startPeriod = 5, endPeriod = 6, weeks = listOf(4,5,6,8,9,10)),
        CourseSession(name = "思想政治理论课Ⅰ", teacher = "邱仁富", location = "良西302教", dayOfWeek = 1, startPeriod = 5, endPeriod = 6, weeks = listOf(11)),
        CourseSession(name = "思想政治理论课Ⅰ", teacher = "邱仁富", location = "", dayOfWeek = 1, startPeriod = 5, endPeriod = 6, weeks = listOf(12,13,14)),
        CourseSession(name = "思想政治理论课Ⅰ", teacher = "邱仁富", location = "", dayOfWeek = 1, startPeriod = 5, endPeriod = 5, weeks = listOf(15)),
        CourseSession(name = "思想政治理论课Ⅰ", teacher = "邱仁富", location = "虚拟教室", dayOfWeek = 1, startPeriod = 5, endPeriod = 6, weeks = listOf(16)),
        CourseSession(name = "世界经济史概论", teacher = "杨丽丽", location = "良东170教", dayOfWeek = 3, startPeriod = 9, endPeriod = 11, weeks = listOf(4,5,7,8)),
        CourseSession(name = "世界经济史概论", teacher = "杨丽丽", location = "良东274教", dayOfWeek = 3, startPeriod = 9, endPeriod = 11, weeks = listOf(6)),
        CourseSession(name = "世界经济史概论", teacher = "杨丽丽", location = "良东149教", dayOfWeek = 3, startPeriod = 9, endPeriod = 10, weeks = listOf(9)),
        CourseSession(name = "中医基础综合实训", teacher = "李峰", location = "中诊技能实训室1", dayOfWeek = 2, startPeriod = 5, endPeriod = 6, weeks = listOf(13,14,15,16)),
        CourseSession(name = "体育Ⅰ", teacher = "王春云", location = "良东体育场", dayOfWeek = 4, startPeriod = 3, endPeriod = 4, weeks = listOf(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17)),
        CourseSession(name = "大学生心理健康教育（含医学心理学）", teacher = "刘钊", location = "良东316教", dayOfWeek = 5, startPeriod = 3, endPeriod = 4, weeks = listOf(4,5,6,7)),
        CourseSession(name = "人工智能概论", teacher = "赵步天", location = "良东258教", dayOfWeek = 1, startPeriod = 9, endPeriod = 11, weeks = listOf(4,5,6,7,8,9,10,11,12,13,14)),
        CourseSession(name = "人工智能概论", teacher = "赵步天", location = "良东258教", dayOfWeek = 1, startPeriod = 9, endPeriod = 9, weeks = listOf(15)),
        CourseSession(name = "大学英语Ⅰ-Ⅱ", teacher = "邵英俊", location = "良东308教", dayOfWeek = 2, startPeriod = 5, endPeriod = 6, weeks = listOf(4,5,6,7,8,9)),
        CourseSession(name = "大学英语Ⅰ-Ⅱ", teacher = "邵英俊", location = "良东308教", dayOfWeek = 2, startPeriod = 5, endPeriod = 5, weeks = listOf(10)),
        CourseSession(name = "大学英语Ⅰ-Ⅱ", teacher = "邵英俊", location = "良东308教", dayOfWeek = 4, startPeriod = 7, endPeriod = 8, weeks = listOf(4,5,6,7,8,9)),
    )
}
