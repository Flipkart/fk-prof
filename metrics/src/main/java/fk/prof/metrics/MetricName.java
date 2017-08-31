package fk.prof.metrics;

import java.util.HashSet;
import java.util.Set;

public enum MetricName {
  S3_Threadpool_Rejection("s3.threadpool.rejection"),

  AW_State_Transition_Failure("aw.state.transition.failure"),
  AW_CpuSampling_Aggregation_Failure("aw.cpusampling.agg.failure"),
  AW_Active_Count("aw.active.count"),
  AW_Work_Success("aw.work.success"),
  AW_Work_Failure("aw.work.failure"),
  AW_Work_Miss("aw.work.miss"),
  AW_Work_Stale("aw.work.stale"),
  AW_Init_Success("aw.init.success"),
  AW_Init_Failure("aw.init.failure"),
  AW_Skip_Unhealthy("aw.skip.unhealthy"),
  AW_Skip_ZeroCoverage("aw.skip.zero.coverage"),
  AW_Expire_Success("aw.expire.success"),
  AW_Expire_Failure("aw.expire.failure"),
  AW_Store_Profile_Complete("aw.store.profile.complete"),
  AW_Store_Summary_Complete("aw.store.summary.complete"),
  AW_Store_Bytes("aw.store.bytes"),
  AW_Store_Failure("aw.store.failure"),
  AW_BuffPool_Borrow("aw.buffpool.borrow"),
  AW_Buffpool_Failure("aw.buffpool.failure"),

  Backend_Unknown_Leader_Request("backend.unknown.leader.request"),
  Backend_Self_Leader_Request("backend.self.leader.request"),
  Backend_Association_Count("backend.assoc.count"),
  Backend_LoadReport_Complete("backend.loadreport.complete"),
  Backend_LoadReport_Reset("backend.loadreport.reset"),
  Backend_LoadReport_Stale("backend.loadreport.stale"),

  Poll_Assoc_Miss("poll.assoc.miss"),
  Poll_Window_Miss("poll.window.miss"),

  Profile_Chunk_Size("profile.chunk.size"),
  Profile_Chunk_Idle("profile.chunk.idle"),
  Profile_Chunk_Bytes("profile.chunk.bytes"),
  Profile_Payload_Corrupt("profile.payload.corrupt"),
  Profile_Payload_Invalid("profile.payload.invalid"),
  Profile_Window_Miss("profile.window.miss"),
  Profile_Wse_Size("profile.wse.size"),
  Profile_Header_Size("profile.header.size"),

  Recorder_Poll_Complete("recorder.poll.complete"),
  Recorder_Poll_Reset("recorder.poll.reset"),
  Recorder_Poll_Stale("recorder.poll.stale"),
  Recorder_Assignment_Available("recorder.assignment.available"),

  WA_Entries_Lock_Timeout("wa.entries.lock.timeout"),
  WA_Entries_Lock_Interrupt("wa.entries.lock.interrupt"),
  WA_Fetch_Failure("wa.fetch.failure"),
  WA_Scheduling_Miss("wa.scheduling.miss"),
  WA_Scheduling_Impossible("wa.scheduling.impossible"),

  Leader_Work_Assoc_Miss("leader.work.assoc.miss"),
  Leader_Work_Policy_Miss("leader.work.policy.miss"),
  Leader_Assoc_Success("leader.assoc.success"),
  Leader_Assoc_Failure("leader.assoc.failure"),
  Leader_LoadReport_Success("leader.loadreport.success"),
  Leader_LoadReport_Failure("leader.loadreport.failure"),

  Election_Task_Failure("election.task.failure"),
  Election_Watch_Failure("election.watch.failure"),
  Election_Completed("election.completed"),
  Election_Relinquished("election.relinquished"),
  Election_Interrupted("election.interrupted"),
  Election_Suicide_Failure("election.suicide.failure"),

  ZK_Backend_Assoc_Load_Failure("zk.backend.assoc.load.failure"),
  ZK_Backend_Assoc_Lock_Timeout("zk.backend.assoc.lock.timeout"),
  ZK_Backend_Assoc_Lock_Interrupt("zk.backend.assoc.lock.interrupt"),
  ZK_Backend_Assoc_Unavailable("zk.backend.assoc.unavailable"),
  ZK_Backend_Assoc_Existing_Invalid("zk.backend.assoc.existing.invalid"),
  ZK_Backend_Assoc_Existing_Healthy("zk.backend.assoc.existing.healthy"),
  ZK_Backend_Assoc_Add("zk.backend.assoc.add"),
  ZK_Backend_Assoc_Remove("zk.backend.assoc.remove"),
  ZK_Backend_Assoc_LoadReport_Existing("zk.backend.assoc.loadreport.existing"),
  ZK_Backend_Assoc_Enqueue_Failure("zk.backend.assoc.enqueue.fail"),

  Daemon_LoadReport_Success("daemon.loadreport.success"),
  Daemon_LoadReport_Failure("daemon.loadreport.failure"),
  Daemon_Unknown_Leader_Request("daemon.unknown.leader.request");

  private static final Set<String> _metrics = new HashSet<>();
  static {
    for(MetricName mName: values()) {
      String name = mName.get();
      if(_metrics.contains(name)) {
        throw new Error("Cannot have metrics with duplicate names");
      }
      _metrics.add(name);
    }
  }

  private String name;

  MetricName(String name) {
    this.name = name;
  }

  public String get() {
    return name;
  }
}
