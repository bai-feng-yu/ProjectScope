package agent_backend.task;

import java.time.Instant;
import java.util.List;
import agent_backend.persistence.model.TaskStatus;

/**
 * 发给前端的完整业务快照，也是判断进度是否变化的比较对象。
 * 排除 workerId、heartbeatAt、leaseUntil 及任务 updatedAt，避免租约续期触发进度事件。
 * record 自动生成按字段比较的 equals，步骤和工具结果也参与比较。
 */
public record TaskSnapshot(String taskId, String task, TaskStatus status, String finalAnswer,
        String failureReason, Instant createdAt, Instant startedAt, Instant finishedAt,
        List<TaskStepResponse> steps) {
    // 固定列表内容，避免外部修改原列表影响快照；这不是对内部 JsonNode 的深拷贝。
    public TaskSnapshot { steps = List.copyOf(steps); }
}
