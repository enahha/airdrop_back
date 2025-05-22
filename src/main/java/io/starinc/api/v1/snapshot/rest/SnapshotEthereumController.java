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

import io.starinc.api.v1.common.vo.CommonVo;
import io.starinc.api.v1.snapshot.mapper.SnapshotMapper;
import io.starinc.api.v1.snapshot.vo.SnapshotMstVo;
import io.starinc.api.v1.snapshot.vo.SnapshotVo;

//@ApiIgnore
@RestController
@Transactional
@RequestMapping(value = "/api")
public class SnapshotEthereumController {
	@Autowired
	private SnapshotMapper snapshotMapper;
//	private String rpcUri = "https://eth-mainnet.g.alchemy.com/nft/v3/h19_HGGOfH3lLPlbxLVcOaW9y6fssIP8/getOwnersForContract?contractAddress=0x8c58a4c09b79535867390746462de0268a98dbdb&withTokenBalances=true";
	private String rpcUri = "https://eth-mainnet.g.alchemy.com/nft/v3/h19_HGGOfH3lLPlbxLVcOaW9y6fssIP8/getOwnersForContract?contractAddress=";
	
	/**
	 * 스냅샷 검색 후 테이블 등록
	 * 
	 * @param contractAddress
	 * @param title
	 * 
	 * @return commonVo
	 * @throws Exception
	 */
	@GetMapping("/snapshot/snapshotEthereum")
	public CommonVo snapshotEthereum(@RequestParam String collectionAddress, @RequestParam String title) throws Exception {
	CommonVo commonVo = new CommonVo();
	commonVo.setResultCd("SUCCESS");
	
	try {
		// 1. snapshot_mst 등록
		SnapshotMstVo snapshotMstVo = new SnapshotMstVo();
		snapshotMstVo.setTitle(title);
		snapshotMstVo.setMainnet("ethereum");
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
		String requestUrl = this.rpcUri + collectionAddress + "&withTokenBalances=true";
		
		// HttpClient 생성
		try (CloseableHttpClient client = HttpClients.createDefault()) { 
			ObjectMapper mapper = new ObjectMapper();
			
			int resultNumber = 1;
			
			HttpGet httpGet = new HttpGet(requestUrl);
			
			// 헤더 설정
			// https://docs.alchemy.com/reference/getownersforcontract-v3
			httpGet.addHeader("Content-Type", "application/json");
			
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
			JsonNode resultArray = returnNode.path("owners");
			
			// snapshotList를 한 번에 쌓아서 DB에 삽입
			List<SnapshotVo> snapshotList = new ArrayList<>();
			
			for (JsonNode ownerObj : resultArray) {
				
				String ownerAddress = ownerObj.path("ownerAddress").asText();
				
				// owner가 보유한 NFT 리스트
				JsonNode tokenBalances = ownerObj.path("tokenBalances");
				
				// NFT 갯수만큼 insert 대상 추가
				for (JsonNode nftObj : tokenBalances) {
					SnapshotVo snapshotVo = new SnapshotVo();
					snapshotVo.setMst_seq(snapshotMstVo.getSeq());
					snapshotVo.setResult_number(resultNumber);
					snapshotVo.setOwner_address(ownerAddress);
					snapshotVo.setNft_id(nftObj.path("tokenId").asText());
					snapshotVo.setNft_name("#" + snapshotVo.getNft_id());
					snapshotVo.setReg_id("SYSTEM");
					snapshotList.add(snapshotVo);
					
					resultNumber++;
				}
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
		}
	} catch (Exception e) {
		e.printStackTrace();
		commonVo.setResultCd("FAIL");
		commonVo.setResultMsg(e.toString());
	}
		return commonVo;
	}
}
