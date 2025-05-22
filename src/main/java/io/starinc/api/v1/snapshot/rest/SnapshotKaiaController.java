package io.starinc.api.v1.snapshot.rest;

import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.starinc.api.v1.common.util.StringUtil;
import io.starinc.api.v1.common.vo.CommonVo;
import io.starinc.api.v1.snapshot.mapper.SnapshotMapper;
import io.starinc.api.v1.snapshot.vo.SnapshotMstVo;
import io.starinc.api.v1.snapshot.vo.SnapshotVo;

//@ApiIgnore
@RestController
@Transactional
@RequestMapping(value = "/api")
public class SnapshotKaiaController {
	@Autowired
	private SnapshotMapper snapshotMapper;
	private String rpcUri = "https://th-api.klaytnapi.com/v2/contract/nft/";
	private int pageSize = 1000; // API 1회 호출 결과 갯수
	
	/**
	 * 스냅샷 검색 후 테이블 등록
	 * 
	 * @param contractAddress
	 * @param title
	 * 
	 * @return commonVo
	 * @throws Exception
	 */
	@GetMapping("/snapshot/snapshotKaia")
	public CommonVo snapshotKaia(@RequestParam String collectionAddress, @RequestParam String title) throws Exception {
	CommonVo commonVo = new CommonVo();
	commonVo.setResultCd("SUCCESS");
	
	try {
		// 1. snapshot_mst 등록
		SnapshotMstVo snapshotMstVo = new SnapshotMstVo();
		snapshotMstVo.setTitle(title);
		snapshotMstVo.setMainnet("kaia");
		snapshotMstVo.setCollection_address(collectionAddress);
		snapshotMstVo.setReg_id("SYSTEM");
		
		// `snapshot_mst` 테이블에 insert 쿼리 실행
		int mstResult = this.snapshotMapper.insertSnapshotMst(snapshotMstVo); // insert된 seq 값을 받음
		if (mstResult <= 0) {
			commonVo.setResultCd("FAIL");
			commonVo.setResultMsg("Failed to insert snapshot_mst or received invalid mstSeq.");
			return commonVo;
		}
		
		// 2. 전체 홀더 조회
		String requestUrl = this.rpcUri + collectionAddress + "/token?size=" + pageSize;
		
		// HttpClient 생성
		try (CloseableHttpClient client = HttpClients.createDefault()) { 
			ObjectMapper mapper = new ObjectMapper();
			
			int resultNumber = 1;
			boolean hasMoreData = true;
			
			String cursor = "";
			
			while (hasMoreData) {
				HttpGet httpGet = new HttpGet(requestUrl + "&cursor=" + cursor);
				
				// 요청 본문 설정
//				ObjectNode paramsNode = mapper.createObjectNode();
//				paramsNode.put("groupKey", "collection");
//				paramsNode.put("groupValue", collectionAddress);
//				paramsNode.put("page", page);
				
//				ObjectNode requestNode = mapper.createObjectNode();
//				requestNode.put("jsonrpc", "2.0");
//				requestNode.put("id", 1);
//				requestNode.put("method", "getAssetsByGroup");
//				requestNode.set("params", paramsNode);
				
//				StringEntity entity = new StringEntity(requestNode.toString(), "UTF-8");
//				httpGet.setEntity(entity);
				
				// 헤더 설정
				// KAS 계정 : ayd1029@gmail.com / ayd801029!
				// https://console.klaytnapi.com/
				httpGet.addHeader("Content-Type", "application/json");
				httpGet.addHeader("x-chain-id", "8217");
				httpGet.addHeader("Authorization", "Basic S0FTS1Y2QlIwQUxXM05BRDlIU1NPUjNDOjJpeXlSYTVROEt2eUZtX3gwZ1lJRmdzbmRtU09wYk52bTdrdmZpRGo=");
				
				// 요청 실행
				HttpResponse response = client.execute(httpGet);
				int responseCode = response.getStatusLine().getStatusCode();
				
				if (responseCode != 200) {
					commonVo.setResultCd("FAIL");
					commonVo.setResultMsg("ResponseCode is not 200.");
					return commonVo;
				}
				
				// JSON 응답 처리
				JsonNode returnNode = mapper.readTree(response.getEntity().getContent());
				JsonNode resultArray = returnNode.path("items");
				
				// 다음 조회 커서
				cursor = returnNode.path("cursor").asText();
				
				// snapshotList를 한 번에 쌓아서 DB에 삽입
				List<SnapshotVo> snapshotList = new ArrayList<>();
				
				for (JsonNode nftInfoObj : resultArray) {
					SnapshotVo snapshotVo = new SnapshotVo();
					
					// 필요한 필드 추출
					snapshotVo.setOwner_address(nftInfoObj.path("owner").asText());
					
					String nftIdHex = nftInfoObj.path("tokenId").asText().replaceAll("0x", "");
					snapshotVo.setNft_id(String.valueOf(Integer.parseInt(nftIdHex, 16)));
					snapshotVo.setNft_name("#" + snapshotVo.getNft_id());
					snapshotVo.setMst_seq(snapshotMstVo.getSeq());
					snapshotVo.setResult_number(resultNumber);
					snapshotVo.setReg_id("SYSTEM");
					snapshotList.add(snapshotVo);
					
					resultNumber++;
				}
				
				// DB에 insert (각 페이지마다 한 번만 호출)
				if (!snapshotList.isEmpty()) {
					int resultCount = this.snapshotMapper.insertSnapshotList(snapshotList);
					if (resultCount == 0) {
						commonVo.setResultCd("FAIL");
						commonVo.setResultMsg("insertSnapshotList failed.");
						return commonVo;
					}
				}
				
				// 결과가 pageSize보다 적다면 더 이상 데이터가 없다는 의미
				// hasMoreData = resultArray.size() == this.pageSize;
				if (StringUtil.isEmpty(cursor)) {
					hasMoreData = false;
				}
				
				// 딜레이 없으면 차단 당하는 듯.
				// Thread.sleep(1000);
			}
		}
	} catch (Exception e) {
		e.printStackTrace();
		commonVo.setResultCd("FAIL");
		commonVo.setResultMsg(e.toString());
	}
		return commonVo;
	}
}
