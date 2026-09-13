package com.bemodel.llm;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("bm_llm_log")
public class LlmLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String callType;
    private String model;
    private String ontologyVersion;
    private String promptDigest;
    private Long latencyMs;
    private Integer success;
    private String errMsg;
    private LocalDateTime createdAt;
}
