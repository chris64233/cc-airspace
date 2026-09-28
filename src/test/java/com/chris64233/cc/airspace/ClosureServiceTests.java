package com.chris64233.cc.airspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.chris64233.cc.airspace.domain.AirSegment;
import com.chris64233.cc.airspace.repo.AirSegmentRepository;
import com.chris64233.cc.airspace.repo.AirspaceClosureRepository;
import com.chris64233.cc.airspace.repo.AirspaceClosureSegmentRepository;
import com.chris64233.cc.airspace.repo.ClosureImpactRepository;
import com.chris64233.cc.airspace.repo.FlightClearanceRepository;
import com.chris64233.cc.airspace.repo.RouteVersionRepository;
import com.chris64233.cc.airspace.repo.SegmentOccupancyEventRepository;
import com.chris64233.cc.airspace.repo.SegmentReservationRepository;
import com.chris64233.cc.airspace.service.ClearanceService;
import com.chris64233.cc.airspace.service.ClosureService;
import com.chris64233.cc.airspace.service.error.ApiException;
import com.chris64233.cc.airspace.service.error.CapacityExceededException;
import com.chris64233.cc.airspace.service.error.ClosureConflictException;
import com.chris64233.cc.airspace.service.error.IdempotentConflictException;
import com.chris64233.cc.airspace.service.error.StateConflictException;
import com.chris64233.cc.airspace.web.dto.ClosureImpactView;
import com.chris64233.cc.airspace.web.dto.ClosureRequest;
import com.chris64233.cc.airspace.web.dto.ClosureRerouteRequest;
import com.chris64233.cc.airspace.web.dto.ClosureView;
import com.chris64233.cc.airspace.web.dto.ClearanceView;
import com.chris64233.cc.airspace.web.dto.RerouteRequest;
import com.chris64233.cc.airspace.web.dto.SubmitClearanceRequest;
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
    private AirspaceClosureSegmentRepository closureSegmentRepository;
    @Autowired
    private ClosureImpactRepository impactRepository;

    private Instant base;

    @BeforeEach
    void setUp() {
        occupancyEventRepository.deleteAll();
        routeVersionRepository.deleteAll();
        reservationRepository.deleteAll();
        impactRepository.deleteAll();
        clearanceRepository.deleteAll();
        closureRepository.deleteAll();
        segmentRepository.deleteAll();
        base = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    }

    // ---------- 关闭识别 ----------

    @Test
    void closureMarksOnlyLegsNotYetEntered() {
        segments(2, "A", "B", "C");
        submit("X", legs("A", "B", "C"));
        submit("Y", legs("A", "B", "C"));
        // X 已飞入 A、B（序号 0、1）；Y 尚未起飞
        clearanceService.reportProgress("X", 2);

        ClosureView closure = closureService.create(closure("K1", List.of("A", "B", "C"))).view();

        List<ClosureImpactView> impacts = closureService.impacts("K1");
        // X 只剩 C 可调整；Y 三个航段都待处理
        assertEquals(List.of("X:C", "Y:A", "Y:B", "Y:C"), impactKeys(impacts));
        assertTrue(impacts.stream().allMatch(i -> i.status().equals("PENDING")));
        // 标记冻结创建时的航线版本与已飞数快照
        ClosureImpactView xImpact = impacts.stream().filter(i -> i.externalNo().equals("X")).findFirst().orElseThrow();
        assertEquals(2, xImpact.segmentOrder());
        assertEquals(2, xImpact.flownLegCount());
        assertEquals(1, xImpact.clearanceVersion());
        assertEquals(1, closure.scopeVersion());
        assertEquals(4, closure.pendingCount());
    }

    @Test
    void closureOnlyCoversFlightsOverlappingTimeWindow() {
        segments("A", "B", "C");
        submit("X", legs("A", "B", "C"));
        // Z 的飞行时间窗在关闭之后，不受影响
        clearanceService.submit(new SubmitClearanceRequest("Z", "AC-1",
                base.plus(4, ChronoUnit.HOURS), base.plus(6, ChronoUnit.HOURS), legs("B")));

        closureService.create(closure("K1",
                base.plus(30, ChronoUnit.MINUTES), base.plus(2, ChronoUnit.HOURS), List.of("B")));

        List<ClosureImpactView> impacts = closureService.impacts("K1");
        assertEquals(List.of("X:B"), impactKeys(impacts));

        // Z 在关闭时段之外仍可正常改道 / 占用 B
        assertTrue(clearanceService.reroute("Z", new RerouteRequest("R9", 1, 0,
                base.plus(5, ChronoUnit.HOURS), legs("B"))).created());
    }

    @Test
    void completedAndCancelledFlightsAreNotMarked() {
        segments(2, "A", "B", "C");
        submit("X", legs("A", "B", "C"));
        submit("Y", legs("A", "B", "C"));
        clearanceService.reportProgress("X", 3);
        clearanceService.cancel("Y");

        closureService.create(closure("K1", List.of("A", "B", "C")));

        assertTrue(closureService.impacts("K1").isEmpty());
    }

    // ---------- 关闭改道：容量同事务切换 ----------

    @Test
    void closureRerouteSwitchesCapacityAndMarksHandled() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        closureService.create(closure("K1", List.of("B")));

        ClosureService.ClosureRerouteOutcome outcome = closureService.confirmReroute("K1", "X",
                closureReroute("CR1", 1, 1, null, legs("D")));

        assertTrue(outcome.created());
        assertEquals(2, outcome.view().version());
        assertEquals(List.of("A", "D"), legCodes(outcome.view()));

        // 替代航段 D 已占用
        assertThrows(CapacityExceededException.class,
                () -> clearanceService.submit(request("W", legs("D"))));

        // 影响标记已处理，记录改道业务号与生效版本
        ClosureImpactView handled = closureService.impacts("K1").get(0);
        assertEquals("HANDLED", handled.status());
        assertEquals("CR1", handled.resolvedRerouteNo());
        assertEquals(2, handled.resolvedRouteVersion());

        // 占用事件：取得 D 后释放 B、C
        List<String> events = clearanceService.occupancyEvents("X").stream()
                .map(e -> e.eventType() + ":" + e.segmentCode()).toList();
        assertEquals(List.of("ACQUIRE:A", "ACQUIRE:B", "ACQUIRE:C",
                "ACQUIRE:D", "RELEASE:B", "RELEASE:C"), events);

        // 取消关闭后 B 可被新航班占用，证明关闭改道已把 B 的容量释放
        closureService.cancel("K1");
        assertTrue(clearanceService.submit(request("V", legs("B"))).created());
    }

    @Test
    void closureRerouteKeepsOperationalHistory() {
        segments("A", "B", "C", "D", "E");
        submit("X", legs("A", "B", "C"));
        closureService.create(closure("K1", List.of("B")));
        closureService.confirmReroute("K1", "X", closureReroute("CR1", 1, 1, null, legs("D")));

        // 航线历史保留全部版本，关闭改道版本记录关闭事件号
        var history = clearanceService.routeHistory("X");
        assertEquals(2, history.size());
        assertEquals(List.of("A", "B", "C"), history.get(0).legs().stream().map(l -> l.segmentCode()).toList());
        assertEquals(List.of("A", "D"), history.get(1).legs().stream().map(l -> l.segmentCode()).toList());
        assertEquals("CR1", history.get(1).rerouteNo());
        assertEquals("K1", history.get(1).closureEventNo());
    }

    // ---------- 旧方案拒绝 ----------

    @Test
    void closureRerouteRejectsRouteStillThroughClosedSegment() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        closureService.create(closure("K1", List.of("B")));

        // 替代航线仍经过 B
        assertThrows(ClosureConflictException.class, () -> closureService.confirmReroute("K1", "X",
                closureReroute("CR1", 1, 1, null, legs("B", "D"))));

        // 原许可与原占用保持不变
        ClearanceView view = clearanceService.get("X");
        assertEquals(1, view.version());
        assertEquals(List.of("A", "B", "C"), legCodes(view));
        assertEquals("PENDING", closureService.impacts("K1").get(0).status());
    }

    @Test
    void staleScopeVersionIsRejectedAfterClosureChanges() {
        segments("A", "B", "C", "D", "E");
        submit("X", legs("A", "B", "C"));
        closureService.create(closure("K1", List.of("B")));
        // 关闭范围变化：版本 1 -> 2
        closureService.reschedule(closure("K1", List.of("B", "C")));
        assertEquals(2, closureService.get("K1").scopeVersion());

        assertThrows(StateConflictException.class, () -> closureService.confirmReroute("K1", "X",
                closureReroute("CR1", 1, 1, null, legs("D"))));

        // 用新版本可以确认；此时受影响航段为 B、C，新航线 A、D 已避开
        var outcome = closureService.confirmReroute("K1", "X",
                closureReroute("CR2", 1, 2, null, legs("D")));
        assertEquals(2, outcome.view().version());
    }

    @Test
    void staleClearanceVersionIsRejected() {
        segments("A", "B", "C", "D", "E");
        submit("X", legs("A", "B", "C"));
        closureService.create(closure("K1", List.of("B")));
        // 普通改道先把许可版本推进到 2（同时避开 B，关闭标记随之处理）
        clearanceService.reroute("X", reroute("R0", 1, 1, legs("E")));

        // 该关闭标记已被普通改道处理，旧的关闭改道方案既无待处理标记、版本也过期
        assertThrows(StateConflictException.class, () -> closureService.confirmReroute("K1", "X",
                closureReroute("CR1", 1, 1, null, legs("D"))));
    }

    @Test
    void reroutePointAfterEarliestAffectedLegIsRejected() {
        segments("A", "B", "C", "D", "E");
        submit("X", legs("A", "B", "C"));
        closureService.create(closure("K1", List.of("B")));

        // 最早受影响航段为序号 1（B），从序号 2 开始改道无法避开 B
        assertThrows(StateConflictException.class, () -> closureService.confirmReroute("K1", "X",
                closureReroute("CR1", 1, 1, 2, legs("E"))));
    }

    @Test
    void enteredLegExpiresAndCannotBeRewritten() {
        segments("A", "B", "C");
        submit("X", legs("A", "B", "C"));
        closureService.create(closure("K1", List.of("A", "B", "C")));

        // 航班飞入 B：A、B 标记过期，只剩 C
        clearanceService.reportProgress("X", 2);
        List<ClosureImpactView> impacts = closureService.impacts("K1");
        assertEquals(List.of("EXPIRED", "EXPIRED", "PENDING"),
                impacts.stream().map(ClosureImpactView::status).toList());

        // 已进入的航段不能反向修改：从序号 1 改道被位置校验拒绝（先于航段解析）
        assertThrows(StateConflictException.class, () -> closureService.confirmReroute("K1", "X",
                closureReroute("CR1", 1, 1, 1, legs("ZZZ"))));
        // 关闭改道只处理剩余的 C：默认起点取最早待处理航段（序号 2）
        segments("D");
        var outcome = closureService.confirmReroute("K1", "X",
                closureReroute("CR2", 1, 1, null, legs("D")));
        assertEquals(List.of("A", "B", "D"), legCodes(outcome.view()));
        // 已执行的运行记录（A、B 占用、过期标记）保留
        assertEquals(List.of("EXPIRED", "EXPIRED", "HANDLED"),
                closureService.impacts("K1").stream().map(ClosureImpactView::status).toList());
    }

    // ---------- 取消关闭 ----------

    @Test
    void cancelClosureReleasesOnlyUnprocessedFlights() {
        segments(2, "A", "B", "C", "D", "E");
        submit("X", legs("A", "B", "C"));
        submit("Y", legs("A", "B", "C"));
        closureService.create(closure("K1", List.of("B")));
        // X 先完成关闭改道；Y 尚未处理
        closureService.confirmReroute("K1", "X", closureReroute("CR1", 1, 1, null, legs("D")));

        ClosureView cancelled = closureService.cancel("K1");

        assertEquals("CANCELLED", cancelled.status());
        List<ClosureImpactView> impacts = closureService.impacts("K1");
        // X 的改道结果保留；Y 的限制解除
        assertEquals("HANDLED", impacts.stream()
                .filter(i -> i.externalNo().equals("X")).findFirst().orElseThrow().status());
        assertEquals("RELEASED", impacts.stream()
                .filter(i -> i.externalNo().equals("Y")).findFirst().orElseThrow().status());
        // X 不会被自动撤销回 A,B,C
        assertEquals(List.of("A", "D"), legCodes(clearanceService.get("X")));
        // B 重新开放，Y 维持原航线也不再违规，新航班可订 B
        assertTrue(clearanceService.submit(request("Z", legs("B"))).created());

        // 重复取消幂等
        assertEquals("CANCELLED", closureService.cancel("K1").status());
    }

    @Test
    void cannotRerouteAgainstCancelledClosure() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        closureService.create(closure("K1", List.of("B")));
        closureService.cancel("K1");

        assertThrows(StateConflictException.class, () -> closureService.confirmReroute("K1", "X",
                closureReroute("CR1", 1, 1, null, legs("D"))));
    }

    // ---------- 普通改道与关闭冲突 ----------

    @Test
    void ordinaryRerouteStillCrossingClosureIsRejected() {
        segments("A", "B", "C", "D", "E");
        submit("X", legs("A", "B", "C"));
        closureService.create(closure("K1", List.of("B")));

        // 普通改道后的航线仍经过 B：拒绝，容量不变
        assertThrows(ClosureConflictException.class,
                () -> clearanceService.reroute("X", reroute("R1", 1, 1, legs("B", "E"))));
        assertEquals(1, clearanceService.get("X").version());
        assertEquals("PENDING", closureService.impacts("K1").get(0).status());

        // 普通改道主动避开 B：允许，关闭标记同步标记为已处理
        clearanceService.reroute("X", reroute("R2", 1, 1, legs("E")));
        assertEquals(List.of("A", "E"), legCodes(clearanceService.get("X")));
        assertEquals("HANDLED", closureService.impacts("K1").get(0).status());
    }

    @Test
    void submitThroughActiveClosureIsRejected() {
        segments("A", "B", "C");
        submit("X", legs("A", "B", "C"));
        closureService.create(closure("K1", List.of("B")));

        assertThrows(ClosureConflictException.class,
                () -> clearanceService.submit(request("W", legs("B"))));
    }

    // ---------- 幂等 ----------

    @Test
    void closureRerouteIsIdempotent() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        closureService.create(closure("K1", List.of("B")));
        ClosureRerouteRequest request = closureReroute("CR1", 1, 1, null, legs("D"));

        assertTrue(closureService.confirmReroute("K1", "X", request).created());
        ClosureService.ClosureRerouteOutcome replay = closureService.confirmReroute("K1", "X", request);
        assertFalse(replay.created());
        assertEquals(2, replay.view().version());
        // 占用事件只记录一次
        assertEquals(6, clearanceService.occupancyEvents("X").size());
    }

    @Test
    void closureRerouteSameNoDifferentContentConflicts() {
        segments("A", "B", "C", "D", "E");
        submit("X", legs("A", "B", "C"));
        closureService.create(closure("K1", List.of("B")));
        closureService.confirmReroute("K1", "X", closureReroute("CR1", 1, 1, null, legs("D")));

        assertThrows(IdempotentConflictException.class, () -> closureService.confirmReroute("K1", "X",
                closureReroute("CR1", 1, 1, null, legs("E"))));
    }

    @Test
    void closureCreateIsIdempotent() {
        segments("A", "B");
        ClosureRequest request = closure("K1", List.of("B"));
        assertTrue(closureService.create(request).created());
        assertFalse(closureService.create(request).created());
        assertThrows(IdempotentConflictException.class,
                () -> closureService.create(closure("K1", List.of("A"))));
    }

    // ---------- 并发：容量约束 ----------

    @Test
    void concurrentClosureReroutesOnlyOneWins() throws Exception {
        segments("A", "B", "C", "D", "E");
        submit("X", legs("A", "B", "C"));
        closureService.create(closure("K1", List.of("B")));

        List<Throwable> failures = new ArrayList<>();
        int successes = runConcurrently(
                () -> closureService.confirmReroute("K1", "X",
                        closureReroute("CR1", 1, 1, null, legs("D"))),
                () -> closureService.confirmReroute("K1", "X",
                        closureReroute("CR2", 1, 1, null, legs("E"))),
                failures);

        assertEquals(1, successes);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0) instanceof StateConflictException);
        assertEquals(2, clearanceService.get("X").version());
        // 失败方没有取得任何容量
        assertEquals(1, closureService.impacts("K1").size());
    }

    @Test
    void concurrentClosureReroutesRespectAlternativeCapacity() throws Exception {
        segments(2, "A", "B", "C");
        segmentRepository.save(new AirSegment("D", 100, 500, 1));
        submit("X", legs("A", "B"));
        submit("Y", legs("A", "B"));
        closureService.create(closure("K1", List.of("B"))); // D 容量 1，只容得下一班

        List<Throwable> failures = new ArrayList<>();
        int successes = runConcurrently(
                () -> closureService.confirmReroute("K1", "X",
                        closureReroute("CX", 1, 1, 1, legs("D"))),
                () -> closureService.confirmReroute("K1", "Y",
                        closureReroute("CY", 1, 1, 1, legs("D"))),
                failures);

        assertEquals(1, successes);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0) instanceof CapacityExceededException);
        // 恰好一班换到 D，另一班保持原航线 A,B 与原占用不变
        List<List<String>> routes = List.of(legCodes(clearanceService.get("X")),
                legCodes(clearanceService.get("Y")));
        assertTrue(routes.contains(List.of("A", "D")));
        assertTrue(routes.contains(List.of("A", "B")));
    }

    @Test
    void concurrentClosureRerouteAndCancelStayConsistent() throws Exception {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        closureService.create(closure("K1", List.of("B")));

        List<Throwable> failures = new ArrayList<>();
        runConcurrently(
                () -> closureService.confirmReroute("K1", "X",
                        closureReroute("CR1", 1, 1, null, legs("D"))),
                () -> closureService.cancel("K1"),
                failures);

        ClosureView closure = closureService.get("K1");
        assertEquals("CANCELLED", closure.status());
        // 改道若生效则结果保留；标记要么 HANDLED 要么 RELEASED，不存在悬空 PENDING
        List<String> statuses = closureService.impacts("K1").stream()
                .map(ClosureImpactView::status).toList();
        assertTrue(statuses.stream().allMatch(s -> s.equals("HANDLED") || s.equals("RELEASED")));
    }

    // ---------- 查询 ----------

    @Test
    void queriesExposeClosuresImpactsAndFlightImpacts() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        closureService.create(closure("K1", List.of("B")));

        assertEquals(List.of("K1"), closureService.list().stream().map(ClosureView::eventNo).toList());
        assertEquals(1, closureService.get("K1").pendingCount());
        assertEquals(1, closureService.impactsOfFlight("X").size());
        assertEquals("K1", closureService.impactsOfFlight("X").get(0).closureEventNo());
    }

    // ---------- 测试工具 ----------

    private void segments(String... codes) {
        segments(1, codes);
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

    private SubmitClearanceRequest request(String externalNo, List<LegRequest> legs) {
        return new SubmitClearanceRequest(externalNo, "AC-1",
                base, base.plus(3, ChronoUnit.HOURS), legs);
    }

    private void submit(String externalNo, List<LegRequest> legs) {
        assertTrue(clearanceService.submit(request(externalNo, legs)).created());
    }

    private RerouteRequest reroute(String rerouteNo, int expectedVersion, int fromLegIndex,
                                   List<LegRequest> legs) {
        return new RerouteRequest(rerouteNo, expectedVersion, fromLegIndex,
                base.plus(2, ChronoUnit.HOURS), legs);
    }

    private ClosureRequest closure(String eventNo, List<String> codes) {
        return closure(eventNo, base.plus(30, ChronoUnit.MINUTES),
                base.plus(2, ChronoUnit.HOURS).plus(30, ChronoUnit.MINUTES), codes);
    }

    private ClosureRequest closure(String eventNo, Instant start, Instant end, List<String> codes) {
        return new ClosureRequest(eventNo, "test", start, end, codes);
    }

    private ClosureRerouteRequest closureReroute(String rerouteNo, int expectedClearanceVersion,
                                                 int expectedScopeVersion, Integer fromLegIndex,
                                                 List<LegRequest> legs) {
        return new ClosureRerouteRequest(rerouteNo, expectedClearanceVersion, expectedScopeVersion,
                fromLegIndex, base.plus(2, ChronoUnit.HOURS), legs);
    }

    private List<String> legCodes(ClearanceView view) {
        return view.legs().stream().map(l -> l.segmentCode()).toList();
    }

    private List<String> impactKeys(List<ClosureImpactView> impacts) {
        return impacts.stream().map(i -> i.externalNo() + ":" + i.segmentCode()).toList();
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
