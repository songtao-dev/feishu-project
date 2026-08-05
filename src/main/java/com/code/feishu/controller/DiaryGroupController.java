package com.code.feishu.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.code.feishu.context.UserContext;
import com.code.feishu.dto.DiaryGroupDTO;
import com.code.feishu.entity.Diary;
import com.code.feishu.entity.DiaryGroup;
import com.code.feishu.entity.DiaryGroupMember;
import com.code.feishu.entity.User;
import com.code.feishu.mapper.DiaryGroupMapper;
import com.code.feishu.mapper.DiaryGroupMemberMapper;
import com.code.feishu.mapper.DiaryMapper;
import com.code.feishu.service.UserService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 多人共享日记本接口（多人共同编辑一棵日记树）。
 *
 *   POST   /api/diary-group                       创建共享日记本（生成邀请码，创建者=owner）
 *   GET    /api/diary-group/list                  我参与的共享本（active）
 *   GET    /api/diary-group/pending               待我确认的邀请/申请（pending 且我是被邀请人）
 *   GET    /api/diary-group/{groupId}             详情 + 成员列表
 *   POST   /api/diary-group/{groupId}/invite      按用户名邀请（→ pending，需被邀请人确认）
 *   POST   /api/diary-group/join-by-code          凭邀请码申请加入（→ pending，需组主确认）
 *   POST   /api/diary-group/{groupId}/accept      接受邀请/申请（pending → active）
 *   POST   /api/diary-group/{groupId}/decline     拒绝邀请/申请（删除 pending 记录）
 *   DELETE /api/diary-group/{groupId}/members/{userId}  移除成员（仅 owner，不能移除自己）
 *   POST   /api/diary-group/{groupId}/leave       退出（owner 退出则转交或解散）
 *   DELETE /api/diary-group/{groupId}             解散共享本（仅 owner）
 *   GET    /api/diary-group/{groupId}/monthly     共享本月度时间轴（所有成员日记，含作者）
 *
 * 权限规则：
 *   - 共享本日记只能由作者本人（author_user_id）修改/删除
 *   - 组主可邀请/移除成员、确认申请，但不能改删别人的日记
 *   - 组主退出时，所有权转交给最早加入的 active 成员；无其他成员则解散
 */
@RestController
@RequestMapping("/api/diary-group")
public class DiaryGroupController {

    private static final int DEFAULT_MAX_MEMBERS = 8;

    /** 心情/天气 emoji 映射（与 DiaryController 保持一致，月度接口返回用） */
    private static final Map<String, String> MOOD_EMOJI = new LinkedHashMap<>();
    private static final Map<String, String> MOOD_NAME = new LinkedHashMap<>();
    private static final Map<String, String> WEATHER_EMOJI = new LinkedHashMap<>();
    static {
        MOOD_EMOJI.put("very_happy", "😄"); MOOD_EMOJI.put("happy", "🙂");
        MOOD_EMOJI.put("ok", "😐");        MOOD_EMOJI.put("emo", "😔");
        MOOD_EMOJI.put("bad", "☹️");       MOOD_EMOJI.put("very_bad", "😢");

        MOOD_NAME.put("very_happy", "非常开心"); MOOD_NAME.put("happy", "开心");
        MOOD_NAME.put("ok", "不错");        MOOD_NAME.put("emo", "有点emo");
        MOOD_NAME.put("bad", "有点糟糕");    MOOD_NAME.put("very_bad", "很糟糕");

        WEATHER_EMOJI.put("sunny", "☀️"); WEATHER_EMOJI.put("cloudy", "☁️");
        WEATHER_EMOJI.put("rainy", "🌧️"); WEATHER_EMOJI.put("snowy", "❄️");
        WEATHER_EMOJI.put("windy", "💨"); WEATHER_EMOJI.put("foggy", "🌫️");
    }

    private static final int PREVIEW_LEN = 15;

    private final DiaryGroupMapper groupMapper;
    private final DiaryGroupMemberMapper memberMapper;
    private final DiaryMapper diaryMapper;
    private final UserService userService;

    public DiaryGroupController(DiaryGroupMapper groupMapper,
                                DiaryGroupMemberMapper memberMapper,
                                DiaryMapper diaryMapper,
                                UserService userService) {
        this.groupMapper = groupMapper;
        this.memberMapper = memberMapper;
        this.diaryMapper = diaryMapper;
        this.userService = userService;
    }

    // ==================== 创建 ====================

    /**
     * 创建共享日记本。
     * 请求：{"name":"我们的日记","inviteUsernames":["alice","bob"]}
     * 成功：{"ok":true,"id":1,"inviteCode":"AB12CD34"}
     */
    @PostMapping
    public Map<String, Object> create(@RequestBody DiaryGroupDTO dto) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (dto == null || dto.getName() == null || dto.getName().isBlank()) {
            resp.put("ok", false); resp.put("msg", "日记本名称不能为空"); return resp;
        }
        if (dto.getName().length() > 64) {
            resp.put("ok", false); resp.put("msg", "名称过长（≤64字）"); return resp;
        }

        DiaryGroup group = new DiaryGroup();
        group.setName(dto.getName().trim());
        group.setOwnerId(userId);
        group.setMaxMembers(DEFAULT_MAX_MEMBERS);
        group.setInviteCode(genInviteCode());
        groupMapper.insert(group);

        // 创建者直接 active
        DiaryGroupMember ownerM = new DiaryGroupMember();
        ownerM.setGroupId(group.getId());
        ownerM.setUserId(userId);
        ownerM.setRole("owner");
        ownerM.setStatus("active");
        ownerM.setJoinTime(LocalDateTime.now());
        memberMapper.insert(ownerM);

        // 可选：同时邀请一批用户名（→ pending）
        List<String> fails = new ArrayList<>();
        if (dto.getInviteUsernames() != null) {
            for (String uname : dto.getInviteUsernames()) {
                if (uname == null || uname.isBlank()) continue;
                String err = inviteUser(group.getId(), uname.trim(), userId);
                if (err != null) fails.add(uname + ":" + err);
            }
        }

        resp.put("ok", true);
        resp.put("id", group.getId());
        resp.put("inviteCode", group.getInviteCode());
        resp.put("msg", "创建成功");
        if (!fails.isEmpty()) resp.put("inviteFails", fails);
        return resp;
    }

    // ==================== 我参与的列表 ====================

    /**
     * 我参与的所有共享本（active）。
     * 返回：[{ id, name, ownerId, ownerName, memberCount, myRole, isOwner, inviteCode(仅owner可见) }]
     */
    @GetMapping("/list")
    public Map<String, Object> list() {
        Long userId = UserContext.getUserId();
        List<DiaryGroupMember> myMembers = memberMapper.selectList(
                new LambdaQueryWrapper<DiaryGroupMember>()
                        .eq(DiaryGroupMember::getUserId, userId)
                        .eq(DiaryGroupMember::getStatus, "active")
        );
        if (myMembers.isEmpty()) return okList(Collections.emptyList());

        List<Long> groupIds = myMembers.stream().map(DiaryGroupMember::getGroupId).collect(Collectors.toList());
        Map<Long, DiaryGroupMember> myMap = myMembers.stream()
                .collect(Collectors.toMap(DiaryGroupMember::getGroupId, m -> m, (a, b) -> a));

        List<DiaryGroup> groups = groupMapper.selectBatchIds(groupIds);
        // 批量统计每个组的 active 成员数
        List<DiaryGroupMember> allActive = memberMapper.selectList(
                new LambdaQueryWrapper<DiaryGroupMember>()
                        .in(DiaryGroupMember::getGroupId, groupIds)
                        .eq(DiaryGroupMember::getStatus, "active")
        );
        Map<Long, Long> countMap = allActive.stream()
                .collect(Collectors.groupingBy(DiaryGroupMember::getGroupId, Collectors.counting()));

        List<Map<String, Object>> list = new ArrayList<>();
        for (DiaryGroup g : groups) {
            DiaryGroupMember my = myMap.get(g.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", g.getId());
            item.put("name", g.getName());
            item.put("ownerId", g.getOwnerId());
            item.put("ownerName", userService.getDisplayName(g.getOwnerId()));
            item.put("memberCount", countMap.getOrDefault(g.getId(), 0L));
            item.put("maxMembers", g.getMaxMembers());
            item.put("myRole", my.getRole());
            item.put("isOwner", "owner".equals(my.getRole()));
            // 仅 owner 可见邀请码
            if ("owner".equals(my.getRole())) item.put("inviteCode", g.getInviteCode());
            item.put("createTime", g.getCreateTime());
            list.add(item);
        }
        // 按 createTime 降序
        list.sort((a, b) -> {
            LocalDateTime ta = (LocalDateTime) a.get("createTime");
            LocalDateTime tb = (LocalDateTime) b.get("createTime");
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });
        return okList(list);
    }

    // ==================== 待确认邀请/申请 ====================

    /**
     * 待我确认的邀请/申请：
     *   - 别人邀请我（pending，我是 user_id）→ 我自己 accept 即可
     *   - 别人凭邀请码申请加入我的组（pending，我是 owner）→ 我 accept
     * 返回：[{ memberId, groupId, groupName, type: invited/applied, fromUserId, fromName, createTime }]
     */
    @GetMapping("/pending")
    public Map<String, Object> pending() {
        Long userId = UserContext.getUserId();
        // 我作为被邀请人的 pending
        List<DiaryGroupMember> invited = memberMapper.selectList(
                new LambdaQueryWrapper<DiaryGroupMember>()
                        .eq(DiaryGroupMember::getUserId, userId)
                        .eq(DiaryGroupMember::getStatus, "pending")
        );
        // 我作为 owner 的组里，别人的 pending 申请
        List<DiaryGroup> myOwned = groupMapper.selectList(
                new LambdaQueryWrapper<DiaryGroup>().eq(DiaryGroup::getOwnerId, userId));
        List<Map<String, Object>> list = new ArrayList<>();
        if (invited.isEmpty() && myOwned.isEmpty()) return okList(list);

        // 收集所有相关 group
        Set<Long> relatedGroupIds = new HashSet<>();
        invited.forEach(m -> relatedGroupIds.add(m.getGroupId()));
        myOwned.forEach(g -> relatedGroupIds.add(g.getId()));
        Map<Long, DiaryGroup> groupMap = groupMapper.selectBatchIds(relatedGroupIds).stream()
                .collect(Collectors.toMap(DiaryGroup::getId, g -> g));

        // 我被邀请的
        for (DiaryGroupMember m : invited) {
            DiaryGroup g = groupMap.get(m.getGroupId());
            if (g == null) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("memberId", m.getId());
            item.put("groupId", g.getId());
            item.put("groupName", g.getName());
            item.put("type", "invited");
            item.put("fromUserId", g.getOwnerId());
            item.put("fromName", userService.getDisplayName(g.getOwnerId()));
            item.put("createTime", m.getCreateTime());
            list.add(item);
        }
        // 别人申请加入我的组（凭邀请码）
        if (!myOwned.isEmpty()) {
            List<DiaryGroupMember> applied = memberMapper.selectList(
                    new LambdaQueryWrapper<DiaryGroupMember>()
                            .in(DiaryGroupMember::getGroupId, myOwned.stream().map(DiaryGroup::getId).collect(Collectors.toList()))
                            .eq(DiaryGroupMember::getStatus, "pending")
                            .ne(DiaryGroupMember::getUserId, userId)
            );
            for (DiaryGroupMember m : applied) {
                DiaryGroup g = groupMap.get(m.getGroupId());
                if (g == null) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("memberId", m.getId());
                item.put("groupId", g.getId());
                item.put("groupName", g.getName());
                item.put("type", "applied");
                item.put("fromUserId", m.getUserId());
                item.put("fromName", userService.getDisplayName(m.getUserId()));
                item.put("createTime", m.getCreateTime());
                list.add(item);
            }
        }
        return okList(list);
    }

    // ==================== 详情 + 成员 ====================

    /**
     * 详情 + 成员列表（仅 active 成员可查）。
     * 返回：{ id, name, ownerId, ownerName, maxMembers, inviteCode(仅owner), members:[{userId,username,nickname,role,status,joinTime}] }
     */
    @GetMapping("/{groupId}")
    public Map<String, Object> detail(@PathVariable Long groupId) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        DiaryGroup group = groupMapper.selectById(groupId);
        if (group == null) { resp.put("ok", false); resp.put("msg", "日记本不存在"); return resp; }
        String authErr = assertActiveMember(groupId, userId);
        if (authErr != null) { resp.put("ok", false); resp.put("msg", authErr); return resp; }

        // 成员列表（active + pending，不含 left）
        List<DiaryGroupMember> members = memberMapper.selectList(
                new LambdaQueryWrapper<DiaryGroupMember>()
                        .eq(DiaryGroupMember::getGroupId, groupId)
                        .ne(DiaryGroupMember::getStatus, "left")
                        .orderByAsc(DiaryGroupMember::getJoinTime)
        );
        List<Map<String, Object>> memberList = new ArrayList<>();
        for (DiaryGroupMember m : members) {
            User u = userService.getByIdRaw(m.getUserId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", m.getUserId());
            item.put("username", u == null ? "" : u.getUsername());
            item.put("nickname", u == null ? "" : (u.getNickname() == null ? "" : u.getNickname()));
            item.put("displayName", userService.getDisplayName(m.getUserId()));
            item.put("role", m.getRole());
            item.put("status", m.getStatus());
            item.put("joinTime", m.getJoinTime());
            memberList.add(item);
        }

        resp.put("ok", true);
        resp.put("id", group.getId());
        resp.put("name", group.getName());
        resp.put("ownerId", group.getOwnerId());
        resp.put("ownerName", userService.getDisplayName(group.getOwnerId()));
        resp.put("maxMembers", group.getMaxMembers());
        resp.put("isOwner", Objects.equals(group.getOwnerId(), userId));
        if (Objects.equals(group.getOwnerId(), userId)) resp.put("inviteCode", group.getInviteCode());
        resp.put("members", memberList);
        return resp;
    }

    // ==================== 邀请（按用户名） ====================

    /**
     * 按用户名邀请（仅 owner）。被邀请人 → pending，需自行确认。
     * 请求：{"username":"alice"}
     */
    @PostMapping("/{groupId}/invite")
    public Map<String, Object> invite(@PathVariable Long groupId, @RequestBody DiaryGroupDTO dto) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        String ownerErr = assertOwner(groupId, userId);
        if (ownerErr != null) { resp.put("ok", false); resp.put("msg", ownerErr); return resp; }
        if (dto == null || dto.getUsername() == null || dto.getUsername().isBlank()) {
            resp.put("ok", false); resp.put("msg", "用户名不能为空"); return resp;
        }
        String err = inviteUser(groupId, dto.getUsername().trim(), userId);
        if (err != null) { resp.put("ok", false); resp.put("msg", err); return resp; }
        resp.put("ok", true); resp.put("msg", "邀请已发出，等待对方确认");
        return resp;
    }

    // ==================== 凭邀请码申请加入 ====================

    /**
     * 凭邀请码申请加入（→ pending，需组主确认）。
     * 请求：{"inviteCode":"AB12CD34"}
     */
    @PostMapping("/join-by-code")
    public Map<String, Object> joinByCode(@RequestBody DiaryGroupDTO dto) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        if (dto == null || dto.getInviteCode() == null || dto.getInviteCode().isBlank()) {
            resp.put("ok", false); resp.put("msg", "邀请码不能为空"); return resp;
        }
        DiaryGroup group = groupMapper.selectOne(
                new LambdaQueryWrapper<DiaryGroup>().eq(DiaryGroup::getInviteCode, dto.getInviteCode().trim())
        );
        if (group == null) { resp.put("ok", false); resp.put("msg", "邀请码无效"); return resp; }

        String err = upsertPendingMember(group.getId(), userId, "member");
        if (err != null) { resp.put("ok", false); resp.put("msg", err); return resp; }
        resp.put("ok", true);
        resp.put("groupId", group.getId());
        resp.put("groupName", group.getName());
        resp.put("msg", "申请已提交，等待组主确认");
        return resp;
    }

    // ==================== 接受 ====================

    /**
     * 接受邀请/申请（pending → active）。
     *   - 被邀请人接受别人对自己的邀请：body 不传 userId 或传自己的
     *   - 组主接受别人的申请：body 传 userId=申请人
     * 请求：{"userId":123}
     */
    @PostMapping("/{groupId}/accept")
    public Map<String, Object> accept(@PathVariable Long groupId, @RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        Long targetUserId = body.get("userId") == null ? userId : Long.valueOf(body.get("userId").toString());

        DiaryGroup group = groupMapper.selectById(groupId);
        if (group == null) { resp.put("ok", false); resp.put("msg", "日记本不存在"); return resp; }

        // 权限：要么是接受给自己的邀请（targetUserId == 自己），要么是组主接受别人的申请
        boolean isSelf = Objects.equals(targetUserId, userId);
        boolean isOwner = Objects.equals(group.getOwnerId(), userId);
        if (!isSelf && !isOwner) {
            resp.put("ok", false); resp.put("msg", "无权操作"); return resp;
        }

        DiaryGroupMember m = memberMapper.selectOne(
                new LambdaQueryWrapper<DiaryGroupMember>()
                        .eq(DiaryGroupMember::getGroupId, groupId)
                        .eq(DiaryGroupMember::getUserId, targetUserId)
        );
        if (m == null) { resp.put("ok", false); resp.put("msg", "邀请/申请记录不存在"); return resp; }
        if (!"pending".equals(m.getStatus())) {
            resp.put("ok", false); resp.put("msg", "该记录状态不可接受：" + m.getStatus()); return resp;
        }
        // 上限校验（active + pending 总数，含本次转 active 后）
        long current = countNonLeftMembers(groupId);
        if (current >= group.getMaxMembers()) {
            resp.put("ok", false); resp.put("msg", "成员已达上限（" + group.getMaxMembers() + "人）"); return resp;
        }
        m.setStatus("active");
        m.setJoinTime(LocalDateTime.now());
        memberMapper.updateById(m);
        resp.put("ok", true); resp.put("msg", "已加入");
        return resp;
    }

    // ==================== 拒绝 ====================

    /**
     * 拒绝邀请/申请（删除 pending 记录）。
     * 请求：{"userId":123}
     */
    @PostMapping("/{groupId}/decline")
    public Map<String, Object> decline(@PathVariable Long groupId, @RequestBody Map<String, Object> body) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        Long targetUserId = body.get("userId") == null ? userId : Long.valueOf(body.get("userId").toString());

        DiaryGroup group = groupMapper.selectById(groupId);
        if (group == null) { resp.put("ok", false); resp.put("msg", "日记本不存在"); return resp; }
        boolean isSelf = Objects.equals(targetUserId, userId);
        boolean isOwner = Objects.equals(group.getOwnerId(), userId);
        if (!isSelf && !isOwner) {
            resp.put("ok", false); resp.put("msg", "无权操作"); return resp;
        }
        DiaryGroupMember m = memberMapper.selectOne(
                new LambdaQueryWrapper<DiaryGroupMember>()
                        .eq(DiaryGroupMember::getGroupId, groupId)
                        .eq(DiaryGroupMember::getUserId, targetUserId)
        );
        if (m == null) { resp.put("ok", false); resp.put("msg", "记录不存在"); return resp; }
        if (!"pending".equals(m.getStatus())) {
            resp.put("ok", false); resp.put("msg", "仅可拒绝待确认记录"); return resp;
        }
        memberMapper.deleteById(m.getId());
        resp.put("ok", true); resp.put("msg", "已拒绝");
        return resp;
    }

    // ==================== 移除成员 ====================

    /**
     * 移除成员（仅 owner，不能移除自己）。
     */
    @DeleteMapping("/{groupId}/members/{userId}")
    public Map<String, Object> removeMember(@PathVariable Long groupId, @PathVariable Long userId) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long cur = UserContext.getUserId();
        String ownerErr = assertOwner(groupId, cur);
        if (ownerErr != null) { resp.put("ok", false); resp.put("msg", ownerErr); return resp; }
        if (Objects.equals(cur, userId)) { resp.put("ok", false); resp.put("msg", "不能移除自己，请使用退出/解散"); return resp; }
        DiaryGroupMember m = memberMapper.selectOne(
                new LambdaQueryWrapper<DiaryGroupMember>()
                        .eq(DiaryGroupMember::getGroupId, groupId)
                        .eq(DiaryGroupMember::getUserId, userId)
        );
        if (m == null || "left".equals(m.getStatus())) {
            resp.put("ok", false); resp.put("msg", "成员不存在"); return resp;
        }
        m.setStatus("left");
        memberMapper.updateById(m);
        resp.put("ok", true); resp.put("msg", "已移除");
        return resp;
    }

    // ==================== 退出 ====================

    /**
     * 退出共享本。
     *   - 非 owner：直接置 left
     *   - owner：若有其他 active 成员则转交所有权；若无则解散
     */
    @PostMapping("/{groupId}/leave")
    public Map<String, Object> leave(@PathVariable Long groupId) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        DiaryGroup group = groupMapper.selectById(groupId);
        if (group == null) { resp.put("ok", false); resp.put("msg", "日记本不存在"); return resp; }
        DiaryGroupMember m = memberMapper.selectOne(
                new LambdaQueryWrapper<DiaryGroupMember>()
                        .eq(DiaryGroupMember::getGroupId, groupId)
                        .eq(DiaryGroupMember::getUserId, userId)
        );
        if (m == null || "left".equals(m.getStatus())) {
            resp.put("ok", false); resp.put("msg", "你不在该日记本中"); return resp;
        }

        boolean isOwner = "owner".equals(m.getRole());
        m.setStatus("left");
        memberMapper.updateById(m);

        if (isOwner) {
            // 找最早加入的其他 active 成员转交
            DiaryGroupMember next = memberMapper.selectOne(
                    new LambdaQueryWrapper<DiaryGroupMember>()
                            .eq(DiaryGroupMember::getGroupId, groupId)
                            .eq(DiaryGroupMember::getStatus, "active")
                            .orderByAsc(DiaryGroupMember::getJoinTime)
                            .last("LIMIT 1")
            );
            if (next != null) {
                next.setRole("owner");
                memberMapper.updateById(next);
                group.setOwnerId(next.getUserId());
                groupMapper.updateById(group);
                resp.put("ok", true);
                resp.put("msg", "已退出，组主已转交给 " + userService.getDisplayName(next.getUserId()));
            } else {
                // 无其他成员 → 解散
                doDissolve(groupId);
                resp.put("ok", true);
                resp.put("msg", "已退出，因无其他成员，日记本已解散");
                resp.put("dissolved", true);
            }
        } else {
            resp.put("ok", true);
            resp.put("msg", "已退出");
        }
        return resp;
    }

    // ==================== 解散 ====================

    /**
     * 解散共享本（仅 owner）。日记记录保留（group_id 仍指向已删除组，无法再访问）。
     */
    @DeleteMapping("/{groupId}")
    public Map<String, Object> dissolve(@PathVariable Long groupId) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        String ownerErr = assertOwner(groupId, userId);
        if (ownerErr != null) { resp.put("ok", false); resp.put("msg", ownerErr); return resp; }
        doDissolve(groupId);
        resp.put("ok", true); resp.put("msg", "已解散");
        return resp;
    }

    // ==================== 共享本月度时间轴 ====================

    /**
     * 共享本月度时间轴（仅 active 成员可查）。
     * 返回结构与 /api/diary/monthly 一致，但每条多 authorUserId / authorName。
     */
    @GetMapping("/{groupId}/monthly")
    public Map<String, Object> monthly(@PathVariable Long groupId,
                                       @RequestParam(required = false) Integer year,
                                       @RequestParam(required = false) Integer month) {
        Map<String, Object> resp = new LinkedHashMap<>();
        Long userId = UserContext.getUserId();
        DiaryGroup group = groupMapper.selectById(groupId);
        if (group == null) { resp.put("ok", false); resp.put("msg", "日记本不存在"); return resp; }
        String authErr = assertActiveMember(groupId, userId);
        if (authErr != null) { resp.put("ok", false); resp.put("msg", authErr); return resp; }

        LocalDate today = LocalDate.now();
        if (year == null)  year = today.getYear();
        if (month == null) month = today.getMonthValue();
        YearMonth ym = YearMonth.of(year, month);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        List<Diary> list = diaryMapper.selectList(
                new LambdaQueryWrapper<Diary>()
                        .eq(Diary::getGroupId, groupId)
                        .ge(Diary::getDiaryDate, start)
                        .le(Diary::getDiaryDate, end)
                        .orderByDesc(Diary::getDiaryDate)
        );

        // 批量取作者名
        Set<Long> authorIds = list.stream()
                .map(Diary::getAuthorUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> authorNameMap = new HashMap<>();
        for (Long aid : authorIds) authorNameMap.put(aid, userService.getDisplayName(aid));

        List<Map<String, Object>> diaries = new ArrayList<>();
        for (Diary d : list) {
            Map<String, Object> item = buildDiaryPreview(d);
            item.put("authorUserId", d.getAuthorUserId());
            item.put("authorName", authorNameMap.getOrDefault(d.getAuthorUserId(), ""));
            item.put("isMine", Objects.equals(d.getAuthorUserId(), userId));
            item.put("status", d.getStatus() != null ? d.getStatus() : "draft");
            diaries.add(item);
        }

        resp.put("ok", true);
        resp.put("groupId", group.getId());
        resp.put("groupName", group.getName());
        resp.put("year", year);
        resp.put("month", month);
        resp.put("daysInMonth", ym.lengthOfMonth());
        resp.put("total", list.size());
        resp.put("diaries", diaries);
        return resp;
    }

    // ==================== 工具方法 ====================

    /** 生成 8 位大写字母数字邀请码 */
    private String genInviteCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 去掉易混 I O 0 1
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        return sb.toString();
    }

    /** 邀请单个用户（内部用，返回错误信息，null=成功） */
    private String inviteUser(Long groupId, String username, Long inviterId) {
        User u = userService.findByUsername(username);
        if (u == null) return "用户不存在：" + username;
        if (Objects.equals(u.getId(), inviterId)) return "不能邀请自己";
        return upsertPendingMember(groupId, u.getId(), "member");
    }

    /**
     * 把某用户在某组的状态置为 pending（邀请/申请通用）。
     * 若已有记录：left → pending（重新发起）；active → "已加入"；pending → "已发出"。
     * 同时做上限校验。
     */
    private String upsertPendingMember(Long groupId, Long userId, String role) {
        DiaryGroup group = groupMapper.selectById(groupId);
        if (group == null) return "日记本不存在";
        long current = countNonLeftMembers(groupId);
        DiaryGroupMember exist = memberMapper.selectOne(
                new LambdaQueryWrapper<DiaryGroupMember>()
                        .eq(DiaryGroupMember::getGroupId, groupId)
                        .eq(DiaryGroupMember::getUserId, userId)
        );
        if (exist != null) {
            if ("active".equals(exist.getStatus())) return "该用户已是成员";
            if ("pending".equals(exist.getStatus())) return "邀请/申请已发出，待确认";
            // left → 重新发起
            if (current >= group.getMaxMembers()) return "成员已达上限（" + group.getMaxMembers() + "人）";
            exist.setStatus("pending");
            exist.setRole(role);
            exist.setJoinTime(null);
            memberMapper.updateById(exist);
            return null;
        }
        if (current >= group.getMaxMembers()) return "成员已达上限（" + group.getMaxMembers() + "人）";
        DiaryGroupMember m = new DiaryGroupMember();
        m.setGroupId(groupId);
        m.setUserId(userId);
        m.setRole(role);
        m.setStatus("pending");
        memberMapper.insert(m);
        return null;
    }

    /** 统计 active + pending 成员数（不含 left） */
    private long countNonLeftMembers(Long groupId) {
        return memberMapper.selectCount(
                new LambdaQueryWrapper<DiaryGroupMember>()
                        .eq(DiaryGroupMember::getGroupId, groupId)
                        .ne(DiaryGroupMember::getStatus, "left")
        );
    }

    /** 校验当前用户是该组 active 成员，返回 null=通过，否则返回错误信息 */
    private String assertActiveMember(Long groupId, Long userId) {
        DiaryGroupMember m = memberMapper.selectOne(
                new LambdaQueryWrapper<DiaryGroupMember>()
                        .eq(DiaryGroupMember::getGroupId, groupId)
                        .eq(DiaryGroupMember::getUserId, userId)
        );
        if (m == null || "left".equals(m.getStatus())) return "你不在该日记本中";
        if ("pending".equals(m.getStatus())) return "邀请/申请待确认，暂不可访问";
        return null;
    }

    /** 校验当前用户是该组 owner，返回 null=通过 */
    private String assertOwner(Long groupId, Long userId) {
        DiaryGroup group = groupMapper.selectById(groupId);
        if (group == null) return "日记本不存在";
        if (!Objects.equals(group.getOwnerId(), userId)) return "无权操作（仅组主）";
        return null;
    }

    /** 解散：删除所有成员记录 + 组记录（日记保留为孤儿） */
    private void doDissolve(Long groupId) {
        memberMapper.delete(new LambdaQueryWrapper<DiaryGroupMember>().eq(DiaryGroupMember::getGroupId, groupId));
        groupMapper.deleteById(groupId);
    }

    private Map<String, Object> okList(List<Map<String, Object>> list) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", true);
        resp.put("total", list.size());
        resp.put("list", list);
        return resp;
    }

    private Map<String, Object> buildDiaryPreview(Diary d) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", d.getId());
        item.put("diaryDate", d.getDiaryDate() != null ? d.getDiaryDate().toString() : null);
        item.put("title", d.getTitle());
        item.put("preview", makePreview(d.getContent()));
        item.put("mood", d.getMood());
        item.put("moodEmoji", MOOD_EMOJI.getOrDefault(d.getMood(), ""));
        item.put("moodName", MOOD_NAME.getOrDefault(d.getMood(), ""));
        item.put("weather", d.getWeather());
        item.put("weatherEmoji", WEATHER_EMOJI.getOrDefault(d.getWeather(), ""));
        item.put("tags", parseTags(d.getTags()));
        return item;
    }

    private String makePreview(String content) {
        if (content == null) return "";
        String[] parts = content.split("\\s+", 2);
        String text = parts[0];
        int[] cps = text.codePoints().toArray();
        if (cps.length <= PREVIEW_LEN) return text;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PREVIEW_LEN; i++) sb.appendCodePoint(cps[i]);
        sb.append("...");
        return sb.toString();
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) return Collections.emptyList();
        return Arrays.stream(tags.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }
}
