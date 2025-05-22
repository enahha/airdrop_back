package io.starinc.api.v1.snapshot.vo;

import io.starinc.api.v1.common.vo.CommonVo;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper=false)
public class SnapshotMstVo extends CommonVo {
	public int seq;
	private String title;
	private String mainnet;
	private String collection_address;
	private String collection_name;
}
