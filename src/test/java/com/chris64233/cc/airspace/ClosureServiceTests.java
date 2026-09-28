package com.chris64233.cc.airspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.chris64233.cc.airspace.domain.AirSegment;
import com.chris64233.cc.airspace.repo.AirSegmentRepository;
import com.chris64233.cc.airspace.repo.AirspaceClosureRepository;
import com.chris64233.cc.airspace.repo.ClosureImpactRepository;
import com.chris64233.cc.airspace.repo.FlightClearanceRepository;
import com.chris64233.cc.airspace.repo.RouteVersionRepository;
import com.chris64233.cc.airspace.repo.SegmentOccupancyEventRepository;
import com.chris64233.cc.airspace.repo.SegmentReservationRepository;
import com.chris64233.cc.airspace.service.ClearanceService;
import com.chris64233.cc.airspace.service.ClosureService;
import com.chris64233.cc.airspace.service.error.ApiException;
import com.chris64233.cc.airspace.service.error.CapacityExceededException;
import com.chris64233.cc.airspace.service.error.IdempotentConflictException;
import com.chris64233.cc.airspace.service.error.ParamInvalidException;
import com.chris64233.cc.airspace.service.error.StateConflictException;
import com.chris64233.cc.airspace.web.dto.ClosureImpactView;
import com.chris64233.cc.airspace.web.dto.ClosureView;
import com.chris64233.cc.airspace.web.dto.CreateClosureRequest;
import com.chris64233.cc.airspace.web.dto.RerouteRequest;
import com.chris64233.cc.airspace.web.dto.ReviseClosureRequest;
import com.chris64233.cc.airspace.web.dto.RouteVersionView;
import com.chris64233.cc.airspace.web.dto.SubmitClearanceRequest.LegRequest;

@SpringBootTest
class ClosureServiceTests {

    @Autowired
    private ClearanceService clearanceService;
    @Autowired
    private ClosureService closureService;
    @Autowired
    private AirSegmentRepository segmentRepository;
    @Autowired
    private FlightClearanceRepository clearanceRepository;
    @Autowired
    private SegmentReservationRepository reservationRepository;
    @Autowired
    private RouteVersionRepository routeVersionRepository;
    @Autowired
    private SegmentOccupancyEventRepository occupancyEventRepository;
    @Autowired
    private AirspaceClosureRepository closureRepository;
    @Autowired
    private ClosureImpactRepository impactRepository;

    private Instant base;

    @BeforeEach
    void setUp() {
        impactRepository.deleteAll();
        closureRepository.deleteAll();
        occupancyEventRepository.deleteAll();
        routeVersionRepository.deleteAll();
        reservationRepository.deleteAll();
        clearanceRepository.deleteAll();
        segmentRepository.deleteAll();
        base = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    }

    // ---------- 关闭事件与受影响航段标记 ----------

    @Test
    void closureMarksOnlyLegsFlightHasNotEntered() {
        segments(2, "A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        submit("Y", legs("A", "B", "C"));
        // X 已飞完 A、B（已飞 2 段）：A、B 不标记，仅待飞的 C 待处理
        clearanceService.reportProgress("X", 2);
        // Y 未起飞：B、C 全部待处理

        ClosureView closure = closure("CL1", List.of("B", "C"), base, base.plus(3, ChronoUnit.HOURS));

        assertEquals("ACTIVE", closure.status());
        assertEquals(1, closure.scopeVersion());
        Map<String, List<ClosureImpactView>> byFlight = impactsByFlight("CL1");
        assertEquals(List.of("C"), legCodes(byFlight.get("X")));
        assertEquals(List.of("B", "C"), legCodes(byFlight.get("Y")));
        byFlight.values().stream().flatMap(List::stream)
                .forEach(i -> assertEquals("PENDING", i.status()));
    }

    @Test
    void closureOnlyMarksFlightsOverlappingTimeWindow() {
        segments("A", "B", "C");
        submit("X", legs("A", "B"));                       // [base, base+3h)
        submitWindow("Y", base.plus(4, ChronoUnit.HOURS), // 与关闭时段不重叠
                base.plus(6, ChronoUnit.HOURS), legs("B"));

        closure("CL1", List.of("B"), base.plus(1, ChronoUnit.HOURS), base.plus(3, ChronoUnit.HOURS));

        Map<String, List<ClosureImpactView>> byFlight = impactsByFlight("CL1");
        assertTrue(byFlight.containsKey("X"));
        assertFalse(byFlight.containsKey("Y"));
    }

    @Test
    void closureCreationIsIdempotent() {
        segments("A", "B");
        CreateClosureRequest request = closureRequest("CL1", List.of("B"),
                base, base.plus(2, ChronoUnit.HOURS));

        assertTrue(closureService.create(request).created());
        ClosureService.CreateOutcome replay = closureService.create(request);
        assertFalse(replay.created());
        assertEquals(1, replay.view().scopeVersion());

        assertThrows(IdempotentConflictException.class, () -> closureService.create(
                closureRequest("CL1", List.of("A"), base, base.plus(2, ChronoUnit.HOURS))));
    }

    @Test
    void closureRejectsInvalidRangeOrSegments() {
        segments("A");
        assertThrows(ParamInvalidException.class, () -> closureService.create(
                closureRequest("CL1", List.of("A"), base.plus(2, ChronoUnit.HOURS), base)));
        assertThrows(ParamInvalidException.class, () -> closureService.create(
                closureRequest("CL2", List.of("MISSING"), base, base.plus(2, ChronoUnit.HOURS))));
    }

    // ---------- 关闭驱动改道：同事务新旧容量切换 ----------

    @Test
    void closureDrivenRerouteSwitchesCapacityAndResolvesImpacts() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        closure("CL1", List.of("B", "C"), base, base.plus(3, ChronoUnit.HOURS));
        assertEquals(2, closureService.impacts("CL1").size());

        ClearanceService.RerouteOutcome outcome = clearanceService.reroute("X",
                closureReroute("R1", 1, 1, "CL1", 1, legs("D")));

        assertTrue(outcome.created());
        assertEquals(List.of("A", "D"), legCodes(outcome.view()));
        // 标记全部解决，记录处理改道号
        List<ClosureImpactView> impacts = closureService.impacts("CL1");
        assertEquals(2, impacts.size());
        impacts.forEach(i -> {
            assertEquals("REROUTED", i.status());
            assertEquals("R1", i.resolvedRerouteNo());
        });
        // 旧航段 B、C 已被本许可释放（时间窗内占用归零），但关闭仍有效、不可再被新申请订走
        assertEquals(0, reservationRepository.countActiveOverlaps(
                segmentRepository.findByCode("B").orElseThrow().getId(), base, base.plus(3, ChronoUnit.HOURS)));
        assertEquals(0, reservationRepository.countActiveOverlaps(
                segmentRepository.findByCode("C").orElseThrow().getId(), base, base.plus(3, ChronoUnit.HOURS)));
        assertThrows(StateConflictException.class,
                () -> clearanceService.submit(request("W", legs("B", "C"))));
        // 替代航段 D 容量已占用：容量约束依旧生效
        assertThrows(CapacityExceededException.class,
                () -> clearanceService.submit(request("Z", legs("D"))));
        // 取消关闭后 B、C 恢复可订（容量早已释放）
        closureService.cancel("CL1");
        assertTrue(clearanceService.submit(request("W", legs("B", "C"))).created());
        // 航线版本记录关闭事件
        RouteVersionView version = clearanceService.routeHistory("X").get(1);
        assertEquals("CL1", version.closureNo());
        assertEquals(1, version.closureScopeVersion());
    }

    @Test
    void rerouteThroughClosedSegmentIsRejected() {
        segments("A", "B", "C", "D", "E");
        submit("X", legs("A", "B", "C"));
        closure("CL1", List.of("C"), base, base.plus(3, ChronoUnit.HOURS));

        // 替代航线仍经过关闭航段 C：拒绝
        assertThrows(StateConflictException.class, () -> clearanceService.reroute("X",
                closureReroute("R1", 1, 1, "CL1", 1, legs("D", "C"))));
        // 普通改道也不得穿越关闭区域
        assertThrows(StateConflictException.class, () -> clearanceService.reroute("X",
                reroute("R2", 1, 1, legs("E", "C"))));
        // 原航线与占用保持不变，标记仍为 PENDING
        assertEquals(List.of("A", "B", "C"), legCodes(clearanceService.get("X")));
        assertEquals("PENDING", closureService.impacts("CL1").get(0).status());
        assertEquals(1, clearanceService.get("X").version());
    }

    @Test
    void submitThroughActiveClosureIsRejected() {
        segments("A", "B");
        closure("CL1", List.of("B"), base, base.plus(3, ChronoUnit.HOURS));

        assertThrows(StateConflictException.class,
                () -> clearanceService.submit(request("X", legs("A", "B"))));
        // 时间窗不重叠的申请允许
        assertTrue(clearanceService.submit(requestWindow("Y", base.plus(4, ChronoUnit.HOURS),
                base.plus(6, ChronoUnit.HOURS), legs("B"))).created());
    }

    // ---------- 旧方案拒绝：范围版本 / 关闭取消 / 航班状态 ----------

    @Test
    void rerouteBasedOnStaleClosureScopeVersionIsRejected() {
        segments("A", "B", "C", "D", "E");
        submit("X", legs("A", "B", "C"));
        closure("CL1", List.of("B"), base, base.plus(3, ChronoUnit.HOURS));

        // 修订范围：范围版本 1 -> 2，PENDING 标记重算
        ClosureView revised = closureService.revise("CL1", new ReviseClosureRequest(
                "扩大", base, base.plus(3, ChronoUnit.HOURS), List.of("B", "C")));
        assertEquals(2, revised.scopeVersion());

        // 基于旧范围版本 1 的方案拒绝
        assertThrows(StateConflictException.class, () -> clearanceService.reroute("X",
                closureReroute("R1", 1, 1, "CL1", 1, legs("D"))));
        // 基于新版本 2 的方案成功
        ClearanceService.RerouteOutcome outcome = clearanceService.reroute("X",
                closureReroute("R2", 1, 1, "CL1", 2, legs("E")));
        assertEquals(List.of("A", "E"), legCodes(outcome.view()));
    }

    @Test
    void rerouteAfterClosureCancelledIsRejectedAsStalePlan() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        closure("CL1", List.of("B"), base, base.plus(3, ChronoUnit.HOURS));

        closureService.cancel("CL1");

        assertThrows(StateConflictException.class, () -> clearanceService.reroute("X",
                closureReroute("R1", 1, 1, "CL1", 1, legs("D"))));
        // 关闭取消后普通改道恢复可用，且不再有受影响标记
        assertTrue(closureService.impacts("CL1").isEmpty());
        assertTrue(clearanceService.reroute("X", reroute("R2", 1, 1, legs("D"))).created());
    }

    @Test
    void rerouteForFlightNoLongerImpactedIsRejected() {
        segments("A", "B", "C", "D", "E");
        submit("X", legs("A", "B", "C"));
        closure("CL1", List.of("C"), base, base.plus(3, ChronoUnit.HOURS));
        // 先用一次普通改道离开 C，PENDING 标记顺带解决
        clearanceService.reroute("X", reroute("R0", 1, 1, legs("D")));
        assertEquals("REROUTED", closureService.impacts("CL1").get(0).status());

        // 再以该关闭事件为由提交改道方案：航班已不受其待处理限制
        assertThrows(StateConflictException.class, () -> clearanceService.reroute("X",
                closureReroute("R1", 2, 1, "CL1", 1, legs("E"))));
    }

    @Test
    void flownLegsAreNotMarkedAndCannotBeRewrittenByClosureReroute() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        clearanceService.reportProgress("X", 2); // A、B 已飞完，C 为下一待飞航段
        closure("CL1", List.of("A", "B", "C"), base, base.plus(3, ChronoUnit.HOURS));

        // 已飞完的 A、B 不产生标记（不可反向修改），仅待飞的 C 被标记
        assertEquals(List.of("C"), legCodes(closureService.impacts("CL1")));
        // 改道起点早于当前飞行位置：拒绝且不释放容量
        assertThrows(StateConflictException.class, () -> clearanceService.reroute("X",
                closureReroute("R1", 1, 1, "CL1", 1, legs("D"))));
        // 从当前衔接位置绕开 C：成功，已飞的 A、B 保留
        ClearanceService.RerouteOutcome outcome = clearanceService.reroute("X",
                closureReroute("R2", 1, 2, "CL1", 1, legs("D")));
        assertEquals(List.of("A", "B", "D"), legCodes(outcome.view()));
    }

    @Test
    void enteringClosedAreaDropsPendingRestrictionButKeepsHistory() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        closure("CL1", List.of("B", "C"), base, base.plus(3, ChronoUnit.HOURS));

        // 航班实际飞入 B：既成运行事实，B 的待处理限制解除；C 仍受限
        clearanceService.reportProgress("X", 2);
        List<ClosureImpactView> impacts = closureService.impacts("CL1");
        assertEquals(List.of("C"), legCodes(impacts));

        // 从当前位置改道离开 C 仍可成功
        clearanceService.reroute("X", closureReroute("R1", 1, 2, "CL1", 1, legs("D")));
        assertEquals(List.of("A", "B", "D"), legCodes(clearanceService.get("X")));
    }

    // ---------- 修订与取消关闭 ----------

    @Test
    void reviseRecomputesOnlyPendingImpacts() {
        segments(2, "A", "B", "C", "D", "E");
        submit("X", legs("A", "B", "C"));
        submit("Y", legs("A", "B", "C"));
        closure("CL1", List.of("B"), base, base.plus(3, ChronoUnit.HOURS));
        // X 已改道，结果为 REROUTED；Y 仍 PENDING
        clearanceService.reroute("X", closureReroute("R1", 1, 1, "CL1", 1, legs("D")));

        ClosureView revised = closureService.revise("CL1", new ReviseClosureRequest(
                null, base, base.plus(3, ChronoUnit.HOURS), List.of("C")));
        assertEquals(2, revised.scopeVersion());

        List<ClosureImpactView> impacts = closureService.impacts("CL1");
        Map<String, List<ClosureImpactView>> byFlight = impacts.stream()
                .collect(Collectors.groupingBy(ClosureImpactView::externalNo));
        // X 的 REROUTED 历史保留（航段 B），不会被重算或撤销
        assertEquals(List.of("B"), legCodes(byFlight.get("X")));
        assertEquals("REROUTED", byFlight.get("X").get(0).status());
        // Y 的旧 PENDING(B) 删除，按新范围标记 C
        assertEquals(List.of("C"), legCodes(byFlight.get("Y")));
        assertEquals(2, byFlight.get("Y").get(0).scopeVersionAtMark());
        // X 的已完成改道不自动撤销
        assertEquals(List.of("A", "D"), legCodes(clearanceService.get("X")));
    }

    @Test
    void cancelClosureOnlyReleasesPendingImpacts() {
        segments(2, "A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        submit("Y", legs("A", "B", "C"));
        closure("CL1", List.of("B", "C"), base, base.plus(3, ChronoUnit.HOURS));
        clearanceService.reroute("X", closureReroute("R1", 1, 1, "CL1", 1, legs("D")));

        ClosureView cancelled = closureService.cancel("CL1");
        assertEquals("CANCELLED", cancelled.status());

        List<ClosureImpactView> impacts = closureService.impacts("CL1");
        // X 的 REROUTED 记录保留，Y 的 PENDING 标记随取消删除
        assertEquals(2, impacts.size());
        impacts.forEach(i -> {
            assertEquals("X", i.externalNo());
            assertEquals("REROUTED", i.status());
        });
        // X 的改道不自动撤销
        assertEquals(List.of("A", "D"), legCodes(clearanceService.get("X")));
        // 重复取消幂等
        assertEquals("CANCELLED", closureService.cancel("CL1").status());
    }

    @Test
    void cancelledOrRevisedClosureDoesNotBlockAfterwards() {
        segments("A", "B");
        closure("CL1", List.of("B"), base, base.plus(3, ChronoUnit.HOURS));
        closureService.cancel("CL1");
        // 关闭取消后航段恢复可订
        assertTrue(clearanceService.submit(request("X", legs("A", "B"))).created());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentClosureReroutesStillObeyCapacity() throws Exception {
        // A、B、C 容得下两架机，替代航段 D 容量仅 1
        segments(2, "A", "B", "C");
        segmentRepository.save(new AirSegment("D", 100, 500, 1));
        segmentRepository.save(new AirSegment("E", 100, 500, 1));
        submit("X", legs("A", "B", "C"));
        submit("Y", legs("A", "B", "C"));
        closure("CL1", List.of("B", "C"), base, base.plus(3, ChronoUnit.HOURS));

        List<Throwable> failures = new ArrayList<>();
        int successes = runConcurrently(
                () -> clearanceService.reroute("X",
                        closureReroute("R1", 1, 1, "CL1", 1, legs("D"))),
                () -> clearanceService.reroute("Y",
                        closureReroute("R2", 1, 1, "CL1", 1, legs("D"))),
                failures);

        // D 容量为 1：只有一个改道成功，另一个因容量不足失败
        assertEquals(1, successes);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0) instanceof CapacityExceededException);
        // 失败者原航线与容量保持不变
        String winner = legCodes(clearanceService.get("X")).equals(List.of("A", "D")) ? "X" : "Y";
        String loser = winner.equals("X") ? "Y" : "X";
        assertEquals(List.of("A", "B", "C"), legCodes(clearanceService.get(loser)));
        List<ClosureImpactView> winnerImpacts = closureService.impacts("CL1").stream()
                .filter(i -> i.externalNo().equals(winner)).toList();
        winnerImpacts.forEach(i -> assertEquals("REROUTED", i.status()));
        closureService.impacts("CL1").stream()
                .filter(i -> i.externalNo().equals(loser))
                .forEach(i -> assertEquals("PENDING", i.status()));
    }

    @Test
    void concurrentRerouteAndClosureCancelStayConsistent() throws Exception {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        closure("CL1", List.of("B"), base, base.plus(3, ChronoUnit.HOURS));

        List<Throwable> failures = new ArrayList<>();
        runConcurrently(
                () -> clearanceService.reroute("X",
                        closureReroute("R1", 1, 1, "CL1", 1, legs("D"))),
                () -> closureService.cancel("CL1"),
                failures);

        // 关闭最终为 CANCELLED；改道要么先于取消成功（结果保留），要么被拒绝
        assertEquals("CANCELLED", closureService.get("CL1").status());
        List<String> finalLegs = legCodes(clearanceService.get("X"));
        if (finalLegs.equals(List.of("A", "D"))) {
            // 改道先生效：REROUTED 记录保留
            assertEquals("REROUTED", closureService.impacts("CL1").get(0).status());
            assertEquals(2, clearanceService.get("X").version());
        } else {
            // 取消先生效：改道被拒绝，PENDING 已随取消删除，航线不变
            assertEquals(List.of("A", "B", "C"), finalLegs);
            assertTrue(closureService.impacts("CL1").isEmpty());
        }
    }

    // ---------- 查询 ----------

    @Test
    void queriesExposeClosuresImpactsAndFlightImpacts() {
        segmentRepository.save(new AirSegment("A", 100, 500, 2));
        segmentRepository.save(new AirSegment("B", 100, 500, 2));
        segmentRepository.save(new AirSegment("C", 100, 500, 1));
        segmentRepository.save(new AirSegment("D", 100, 500, 1));
        submit("X", legs("A", "B"));
        submit("Y", legs("B", "C"));
        closure("CL1", List.of("B"), base, base.plus(3, ChronoUnit.HOURS));
        closure("CL2", List.of("C"), base, base.plus(3, ChronoUnit.HOURS));

        List<ClosureView> all = closureService.list();
        assertEquals(List.of("CL1", "CL2"), all.stream().map(ClosureView::closureNo).toList());

        List<ClosureImpactView> cl1 = closureService.impacts("CL1");
        assertEquals(List.of("X", "Y"), cl1.stream().map(ClosureImpactView::externalNo).toList());

        // 按航班查询：Y 同时受 CL1(B) 与 CL2(C) 影响
        List<ClosureImpactView> forY = closureService.impactsForFlight("Y");
        assertEquals(List.of("CL1", "CL2"),
                forY.stream().map(ClosureImpactView::closureNo).toList());
        assertEquals(List.of("B", "C"),
                forY.stream().map(ClosureImpactView::segmentCode).toList());

        // Y 从序号 0 整体改道到 D，绕开 CL1 的 B（顺带绕开 CL2 的 C）
        clearanceService.reroute("Y", closureReroute("R1", 1, 0, "CL1", 1, legs("D")));
        List<ClosureImpactView> after = closureService.impactsForFlight("Y");
        assertEquals(2, after.size());
        after.forEach(i -> {
            assertEquals("REROUTED", i.status());
            assertEquals("R1", i.resolvedRerouteNo());
            assertNotNull(i.resolvedAt());
        });
        ClosureImpactView resolved = closureService.impacts("CL1").stream()
                .filter(i -> i.externalNo().equals("Y")).findFirst().orElseThrow();
        assertEquals("REROUTED", resolved.status());
    }

    // ---------- 测试工具 ----------

    private void segments(String... codes) {
        for (String code : codes) {
            segmentRepository.save(new AirSegment(code, 100, 500, 1));
        }
    }

    private void segments(int capacity, String... codes) {
        for (String code : codes) {
            segmentRepository.save(new AirSegment(code, 100, 500, capacity));
        }
    }

    private List<LegRequest> legs(String... codes) {
        List<LegRequest> result = new ArrayList<>();
        for (String code : codes) {
            result.add(new LegRequest(code, 200));
        }
        return result;
    }

    private com.chris64233.cc.airspace.web.dto.SubmitClearanceRequest request(
            String externalNo, List<LegRequest> legs) {
        return requestWindow(externalNo, base, base.plus(3, ChronoUnit.HOURS), legs);
    }

    private com.chris64233.cc.airspace.web.dto.SubmitClearanceRequest requestWindow(
            String externalNo, Instant start, Instant end, List<LegRequest> legs) {
        return new com.chris64233.cc.airspace.web.dto.SubmitClearanceRequest(
                externalNo, "AC-1", start, end, legs);
    }

    private void submit(String externalNo, List<LegRequest> legs) {
        assertTrue(clearanceService.submit(request(externalNo, legs)).created());
    }

    private void submitWindow(String externalNo, Instant start, Instant end, List<LegRequest> legs) {
        assertTrue(clearanceService.submit(requestWindow(externalNo, start, end, legs)).created());
    }

    private CreateClosureRequest closureRequest(String closureNo, List<String> codes,
                                                Instant start, Instant end) {
        return new CreateClosureRequest(closureNo, null, start, end, codes);
    }

    private ClosureView closure(String closureNo, List<String> codes, Instant start, Instant end) {
        return closureService.create(closureRequest(closureNo, codes, start, end)).view();
    }

    private RerouteRequest reroute(String rerouteNo, int expectedVersion, int fromLegIndex,
                                   List<LegRequest> legs) {
        return new RerouteRequest(rerouteNo, expectedVersion, fromLegIndex,
                base.plus(2, ChronoUnit.HOURS), legs);
    }

    private RerouteRequest closureReroute(String rerouteNo, int expectedVersion, int fromLegIndex,
                                          String closureNo, int closureScopeVersion,
                                          List<LegRequest> legs) {
        return new RerouteRequest(rerouteNo, expectedVersion, fromLegIndex,
                base.plus(2, ChronoUnit.HOURS), legs, closureNo, closureScopeVersion);
    }

    private List<String> legCodes(com.chris64233.cc.airspace.web.dto.ClearanceView view) {
        return view.legs().stream().map(l -> l.segmentCode()).toList();
    }

    private List<String> legCodes(List<ClosureImpactView> impacts) {
        return impacts.stream().map(ClosureImpactView::segmentCode).toList();
    }

    private Map<String, List<ClosureImpactView>> impactsByFlight(String closureNo) {
        return closureService.impacts(closureNo).stream()
                .collect(Collectors.groupingBy(ClosureImpactView::externalNo));
    }

    private int runConcurrently(Runnable first, Runnable second, List<Throwable> failures)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (Runnable task : List.of(first, second)) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                go.await(5, TimeUnit.SECONDS);
                try {
                    task.run();
                    return true;
                } catch (ApiException e) {
                    synchronized (failures) {
                        failures.add(e);
                    }
                    return false;
                }
            }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();
        int successes = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(30, TimeUnit.SECONDS)) {
                successes++;
            }
        }
        executor.shutdownNow();
        return successes;
    }
}
