package com.chris64233.cc.airspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.chris64233.cc.airspace.repo.FlightClearanceRepository;
import com.chris64233.cc.airspace.repo.RouteVersionRepository;
import com.chris64233.cc.airspace.repo.SegmentOccupancyEventRepository;
import com.chris64233.cc.airspace.repo.SegmentReservationRepository;
import com.chris64233.cc.airspace.service.ClearanceService;
import com.chris64233.cc.airspace.service.error.ApiException;
import com.chris64233.cc.airspace.service.error.CapacityExceededException;
import com.chris64233.cc.airspace.service.error.IdempotentConflictException;
import com.chris64233.cc.airspace.service.error.ParamInvalidException;
import com.chris64233.cc.airspace.service.error.StateConflictException;
import com.chris64233.cc.airspace.web.dto.ClearanceView;
import com.chris64233.cc.airspace.web.dto.LegConflictView;
import com.chris64233.cc.airspace.web.dto.OccupancyEventView;
import com.chris64233.cc.airspace.web.dto.RerouteRequest;
import com.chris64233.cc.airspace.web.dto.RouteVersionView;
import com.chris64233.cc.airspace.web.dto.SubmitClearanceRequest;
import com.chris64233.cc.airspace.web.dto.SubmitClearanceRequest.LegRequest;

@SpringBootTest
class RerouteServiceTests {

    @Autowired
    private ClearanceService clearanceService;
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

    private Instant base;

    @BeforeEach
    void setUp() {
        occupancyEventRepository.deleteAll();
        routeVersionRepository.deleteAll();
        reservationRepository.deleteAll();
        clearanceRepository.deleteAll();
        segmentRepository.deleteAll();
        base = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    }

    // ---------- 改道基本流程 ----------

    @Test
    void rerouteSwitchesRouteAndCapacityAtomically() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));

        ClearanceService.RerouteOutcome outcome = clearanceService.reroute("X",
                reroute("R1", 1, 1, legs("D")));

        assertTrue(outcome.created());
        assertEquals(2, outcome.view().version());
        assertEquals(List.of("A", "D"), legCodes(outcome.view()));

        // 旧航段 B、C 已释放，新航段 D 已占用
        assertTrue(clearanceService.submit(request("Y", legs("B", "C"))).created());
        assertThrows(CapacityExceededException.class,
                () -> clearanceService.submit(request("Z", legs("D"))));
    }

    @Test
    void rerouteKeepsPrefixAndAppendsNewTail() {
        segments("A", "B", "C", "D", "E");
        submit("X", legs("A", "B", "C"));

        ClearanceService.RerouteOutcome outcome = clearanceService.reroute("X",
                reroute("R1", 1, 1, legs("D", "E")));

        assertEquals(List.of("A", "D", "E"), legCodes(outcome.view()));
        List<RouteVersionView> history = clearanceService.routeHistory("X");
        assertEquals(2, history.size());
        assertEquals(List.of("A", "B", "C"), history.get(0).legs().stream().map(l -> l.segmentCode()).toList());
        assertEquals(List.of("A", "D", "E"), history.get(1).legs().stream().map(l -> l.segmentCode()).toList());
        assertEquals("R1", history.get(1).rerouteNo());
    }

    // ---------- 幂等 ----------

    @Test
    void rerouteIsIdempotentByRerouteNo() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        RerouteRequest request = reroute("R1", 1, 1, legs("D"));

        assertTrue(clearanceService.reroute("X", request).created());
        ClearanceService.RerouteOutcome replay = clearanceService.reroute("X", request);

        assertFalse(replay.created());
        assertEquals(2, replay.view().version());
        assertEquals(2, clearanceService.routeHistory("X").size());
        // 占用事件只记录一次（3 次初始 ACQUIRE + 改道的 1 ACQUIRE + 2 RELEASE）
        assertEquals(6, clearanceService.occupancyEvents("X").size());
    }

    @Test
    void rerouteSameNoWithDifferentContentConflicts() {
        segments("A", "B", "C", "D", "E");
        submit("X", legs("A", "B", "C"));
        clearanceService.reroute("X", reroute("R1", 1, 1, legs("D")));

        assertThrows(IdempotentConflictException.class,
                () -> clearanceService.reroute("X", reroute("R1", 1, 1, legs("E"))));
    }

    // ---------- 失败时原许可与原占用保持不变 ----------

    @Test
    void rerouteFailsWhenCapacityInsufficientAndKeepsOriginal() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        submit("W", legs("D")); // D 容量 1，已被占满

        assertThrows(CapacityExceededException.class,
                () -> clearanceService.reroute("X", reroute("R1", 1, 1, legs("D"))));

        ClearanceView view = clearanceService.get("X");
        assertEquals(1, view.version());
        assertEquals(List.of("A", "B", "C"), legCodes(view));
        // 原占用未释放：B 仍不可再订
        assertThrows(CapacityExceededException.class,
                () -> clearanceService.submit(request("Y", legs("B"))));
        // 没有任何改道版本与事件
        assertEquals(1, clearanceService.routeHistory("X").size());
        assertEquals(3, clearanceService.occupancyEvents("X").size());
    }

    @Test
    void rerouteFailsWhenVersionStaleAndKeepsOriginal() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));

        assertThrows(StateConflictException.class,
                () -> clearanceService.reroute("X", reroute("R1", 5, 1, legs("D"))));

        assertEquals(1, clearanceService.get("X").version());
        assertThrows(CapacityExceededException.class,
                () -> clearanceService.submit(request("Y", legs("B"))));
    }

    @Test
    void rerouteRejectsCancelledOrCompletedClearance() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        clearanceService.cancel("X");
        assertThrows(StateConflictException.class,
                () -> clearanceService.reroute("X", reroute("R1", 1, 1, legs("D"))));

        submit("Y", legs("A", "B", "C"));
        clearanceService.reportProgress("Y", 3);
        assertEquals("COMPLETED", clearanceService.get("Y").status());
        assertThrows(StateConflictException.class,
                () -> clearanceService.reroute("Y", reroute("R2", 1, 1, legs("D"))));
        // 已完成的许可也不能取消
        assertThrows(StateConflictException.class, () -> clearanceService.cancel("Y"));
    }

    // ---------- 飞行位置与已飞航段保护 ----------

    @Test
    void flownLegsCannotBeRewritten() {
        segments("A", "B", "C", "D", "E");
        submit("X", legs("A", "B", "C"));
        clearanceService.reportProgress("X", 1);

        // 改道起点早于当前位置：拒绝
        assertThrows(StateConflictException.class,
                () -> clearanceService.reroute("X", reroute("R1", 1, 0, legs("E"))));
        // 从当前可衔接位置开始：允许，已飞的 A 保留
        ClearanceService.RerouteOutcome outcome = clearanceService.reroute("X",
                reroute("R2", 1, 1, legs("D")));
        assertEquals(List.of("A", "D"), legCodes(outcome.view()));
    }

    @Test
    void stalePositionRerouteDoesNotReleaseCapacity() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        clearanceService.reportProgress("X", 2);

        // 基于旧位置（fromLegIndex=1 < 已飞 2）的改道：拒绝且不释放任何容量
        assertThrows(StateConflictException.class,
                () -> clearanceService.reroute("X", reroute("R1", 1, 1, legs("D"))));

        assertEquals(List.of("A", "B", "C"), legCodes(clearanceService.get("X")));
        assertThrows(CapacityExceededException.class,
                () -> clearanceService.submit(request("Y", legs("B"))));
        assertThrows(CapacityExceededException.class,
                () -> clearanceService.submit(request("Z", legs("C"))));
    }

    @Test
    void staleProgressReportIsIgnored() {
        segments("A", "B", "C");
        submit("X", legs("A", "B", "C"));
        clearanceService.reportProgress("X", 2);

        ClearanceView view = clearanceService.reportProgress("X", 1);
        assertEquals(2, view.flownLegCount());

        // 飞完全部航段后许可完成
        ClearanceView done = clearanceService.reportProgress("X", 3);
        assertEquals("COMPLETED", done.status());
    }

    // ---------- 参数与高度约束 ----------

    @Test
    void rerouteValidatesAltitudeRange() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));

        assertThrows(ParamInvalidException.class, () -> clearanceService.reroute("X",
                new RerouteRequest("R1", 1, 1, base.plus(2, ChronoUnit.HOURS),
                        List.of(new LegRequest("D", 999)))));
    }

    @Test
    void rerouteValidatesEffectiveTimeAndRange() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));

        // 生效时间早于当前时间
        assertThrows(ParamInvalidException.class, () -> clearanceService.reroute("X",
                new RerouteRequest("R1", 1, 1, Instant.now().minus(1, ChronoUnit.HOURS), legs("D"))));
        // 生效时间晚于许可结束时间
        assertThrows(ParamInvalidException.class, () -> clearanceService.reroute("X",
                new RerouteRequest("R2", 1, 1, base.plus(5, ChronoUnit.HOURS), legs("D"))));
        // 改道起点超出航线范围
        assertThrows(ParamInvalidException.class,
                () -> clearanceService.reroute("X", reroute("R3", 1, 3, legs("D"))));
        // 新航段与保留前缀重复
        assertThrows(ParamInvalidException.class,
                () -> clearanceService.reroute("X", reroute("R4", 1, 1, legs("A"))));
    }

    // ---------- 历史版本与占用事件 ----------

    @Test
    void rerouteRecordsRouteVersionsAndOccupancyEvents() {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));
        clearanceService.reroute("X", reroute("R1", 1, 1, legs("D")));
        clearanceService.cancel("X");

        List<OccupancyEventView> events = clearanceService.occupancyEvents("X");
        assertEquals(
                List.of("ACQUIRE:A", "ACQUIRE:B", "ACQUIRE:C",
                        "ACQUIRE:D", "RELEASE:B", "RELEASE:C",
                        "RELEASE:A", "RELEASE:D"),
                events.stream().map(e -> e.eventType() + ":" + e.segmentCode()).toList());
        // 改道事件携带业务号与版本
        assertEquals("R1", events.get(3).rerouteNo());
        assertEquals(2, events.get(3).routeVersion());

        List<RouteVersionView> history = clearanceService.routeHistory("X");
        assertEquals(2, history.size());
        assertEquals(1, history.get(0).version());
        assertEquals(2, history.get(1).version());
    }

    // ---------- 冲突航段查询 ----------

    @Test
    void conflictsReportOverbookedSegments() {
        segment("A", 2);
        segment("B", 1);
        submit("X", legs("A", "B"));
        submit("Y", legs("A"));

        // 容量充足时无冲突
        assertTrue(clearanceService.conflicts("X").isEmpty());

        // 调小 A 容量后出现冲突
        AirSegment a = segmentRepository.findByCode("A").orElseThrow();
        a.setCapacity(1);
        segmentRepository.save(a);

        List<LegConflictView> conflicts = clearanceService.conflicts("X");
        assertEquals(1, conflicts.size());
        assertEquals("A", conflicts.get(0).segmentCode());
        assertEquals(1, conflicts.get(0).overlappingActiveCount());
    }

    // ---------- 并发 ----------

    @Test
    void concurrentReroutesOnSameVersionOnlyOneWins() throws Exception {
        segments("A", "B", "C", "D", "E");
        submit("X", legs("A", "B", "C"));

        List<Throwable> failures = new ArrayList<>();
        int successes = runConcurrently(
                () -> clearanceService.reroute("X", reroute("R1", 1, 1, legs("D"))),
                () -> clearanceService.reroute("X", reroute("R2", 1, 1, legs("E"))),
                failures);

        assertEquals(1, successes);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0) instanceof StateConflictException);
        assertEquals(2, clearanceService.get("X").version());
    }

    @Test
    void concurrentRerouteAndCancelStayConsistent() throws Exception {
        segments("A", "B", "C", "D");
        submit("X", legs("A", "B", "C"));

        List<Throwable> failures = new ArrayList<>();
        runConcurrently(
                () -> clearanceService.reroute("X", reroute("R1", 1, 1, legs("D"))),
                () -> clearanceService.cancel("X"),
                failures);

        // 无论谁先谁后，最终许可已取消，且全部航段容量都已释放
        ClearanceView view = clearanceService.get("X");
        assertEquals("CANCELLED", view.status());
        assertTrue(clearanceService.submit(request("Y", legs("A", "B", "C", "D"))).created());
        // 若改道曾生效，历史版本完整保留
        if (view.version() == 2) {
            assertEquals(2, clearanceService.routeHistory("X").size());
        }
    }

    // ---------- 测试工具 ----------

    private void segments(String... codes) {
        for (String code : codes) {
            segment(code, 1);
        }
    }

    private void segment(String code, int capacity) {
        segmentRepository.save(new AirSegment(code, 100, 500, capacity));
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

    private List<String> legCodes(ClearanceView view) {
        return view.legs().stream().map(l -> l.segmentCode()).toList();
    }

    /**
     * 两个任务并发执行（同一闩门同时放行），返回成功数，异常收集到 failures。
     */
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
