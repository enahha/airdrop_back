package io.starinc.api.v1.snapshot.rest;

import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.starinc.api.v1.common.vo.CommonVo;
import io.starinc.api.v1.snapshot.mapper.SnapshotMapper;
import io.starinc.api.v1.snapshot.vo.SnapshotMstVo;
import io.starinc.api.v1.snapshot.vo.SnapshotVo;

//@ApiIgnore
@RestController
@Transactional
@RequestMapping(value = "/api")
public class SnapshotSolanaController {
	
	private static final Logger logger = LoggerFactory.getLogger(SnapshotSolanaController.class);
	
	@Autowired
	private SnapshotMapper snapshotMapper;
	
	private String heliusGetAssetsByGroupUrl = "https://mainnet.helius-rpc.com/";
	private String heliusApiKey = "55181376-951d-4dca-ade1-79f9f82060a4";
	
	private String magicEdenListingAddress = "2aSJBUGpWWUZty3dafov1Z8Edw3YPA6Z1e2X3aqXu27i";
	
	
	/**
	 * 스냅샷 검색 후 테이블 등록
	 * 
	 * @param contractAddress
	 * @param title
	 * 
	 * @return commonVo
	 * @throws Exception
	 */
	@GetMapping("/snapshot/snapshotSolana")
	public CommonVo snapshotSolana(@RequestParam String collectionAddress, @RequestParam String title) throws Exception {
	CommonVo commonVo = new CommonVo();
	commonVo.setResultCd("SUCCESS");
	
	try {
		// 1. snapshot_mst 등록
		SnapshotMstVo snapshotMstVo = new SnapshotMstVo();
		snapshotMstVo.setTitle(title);
		snapshotMstVo.setMainnet("solana");
		snapshotMstVo.setCollection_address(collectionAddress);
		snapshotMstVo.setReg_id("SYSTEM");
		
		// `snapshot_mst` 테이블에 insert 쿼리 실행
		int mstResult = this.snapshotMapper.insertSnapshotMst(snapshotMstVo); // insert된 seq 값을 받음
		if (mstResult <= 0) {
			commonVo.setResultCd("FAIL");
			commonVo.setResultMsg("Failed to insert snapshot_mst or received invalid mstSeq.");
			return commonVo;
		}
		
		// 2. getAssetsByGroup 조회
		String requestUrl = this.heliusGetAssetsByGroupUrl + "?api-key=" + this.heliusApiKey;
		
		// HttpClient 생성
		try (CloseableHttpClient client = HttpClients.createDefault()) { 
			ObjectMapper mapper = new ObjectMapper();
			int page = 1;
			int resultNumber = 1;
			boolean hasMoreData = true;
			
			while (hasMoreData) {
				HttpPost httpPost = new HttpPost(requestUrl);
				
				// 요청 본문 설정
				ObjectNode paramsNode = mapper.createObjectNode();
				paramsNode.put("groupKey", "collection");
				paramsNode.put("groupValue", collectionAddress);
				paramsNode.put("page", page);
				
				ObjectNode requestNode = mapper.createObjectNode();
				requestNode.put("jsonrpc", "2.0");
				requestNode.put("id", 1);
				requestNode.put("method", "getAssetsByGroup");
				requestNode.set("params", paramsNode);
				
				StringEntity entity = new StringEntity(requestNode.toString(), "UTF-8");
				httpPost.setEntity(entity);
				
				// 헤더 설정
				httpPost.addHeader("Content-Type", "application/json");
				
				// 요청 실행
				HttpResponse response = client.execute(httpPost);
				int responseCode = response.getStatusLine().getStatusCode();
				
				if (responseCode != 200) {
					commonVo.setResultCd("FAIL");
					commonVo.setResultMsg("ResponseCode is not 200.");
					return commonVo;
				}
				
				// JSON 응답 처리
				JsonNode returnNode = mapper.readTree(response.getEntity().getContent());
				JsonNode resultArray = returnNode.path("result").path("items");
				
				// snapshotList를 한 번에 쌓아서 DB에 삽입
				List<SnapshotVo> snapshotList = new ArrayList<>();
				
				for (JsonNode nftInfoObj : resultArray) {
					SnapshotVo snapshotVo = new SnapshotVo();
					
					// 필요한 필드 추출
					snapshotVo.setOwner_address(nftInfoObj.path("ownership").path("owner").asText());
					snapshotVo.setJson_uri(nftInfoObj.path("content").path("json_uri").asText());
					snapshotVo.setNft_id(nftInfoObj.path("id").asText());
					snapshotVo.setNft_name(nftInfoObj.path("content").path("metadata").path("name").asText());
					
					// Rarity 추출
					JsonNode attributes = nftInfoObj.path("content").path("metadata").path("attributes");
					for (JsonNode attribute : attributes) {
						if ("Rarity".equals(attribute.path("trait_type").asText())) {
							snapshotVo.setRarity(attribute.path("value").asText());
							break;
						}
					}
					
					snapshotVo.setMst_seq(snapshotMstVo.getSeq());
					snapshotVo.setResult_number(resultNumber);
					snapshotVo.setReg_id("SYSTEM");
					
					
					////////////////////////////////////////////////////////////////////////////////////
					// owner_address가 2aSJBUGpWWUZty3dafov1Z8Edw3YPA6Z1e2X3aqXu27i (매직에덴 상장시 cNFT가 이동하는 주소)인 경우
					// 트랜잭션 히스토리 조회해서 Wallet 주소(상장시 owner) 추출 후 owner_address에 재설정
					// 
					// 1. getSignaturesForAsset 으로 트랜잭션 키 취득 (index 1의 데이터가 Transfer 이고 제일 마지막 데이터여야 함)
					// ex) 5PW9zWg6QNAfKc3RAqn7a1LGqGjsusxXfUZi2sRyiKcCrMwEc63uZ9i8M5grYXuejXT5taN8THzcBgjyZHX3xhTD
					// 
					// 2. 1의 key로 getTransaction 결과에서 하기 정보 추출
					//   "result": {
					//     "transaction": {
					//       "message": {
					//         "accountKeys": [
					//           "4BAkRgEda9zjq2jdk1YsiDLhYd7wHTcgWwzLbAfMbfMC",
					////////////////////////////////////////////////////////////////////////////////////
					if (this.magicEdenListingAddress.equals(snapshotVo.getOwner_address())) {
						// ■■■■■■■■■■ 1. getSignaturesForAsset으로 트랜잭션 시그니처 찾기 ■■■■■■■■■■
						boolean hasMoreDataGetSignaturesForAsset = true;
						int pageGetSignaturesForAsset = 1;
						
						List<String> signatureList = new ArrayList<>();
						
						while (hasMoreDataGetSignaturesForAsset) {
							// 요청 본문 설정
							ObjectNode paramsNodeGetSignaturesForAsset = mapper.createObjectNode();
							paramsNodeGetSignaturesForAsset.put("id", snapshotVo.getNft_id());
							paramsNodeGetSignaturesForAsset.put("page", pageGetSignaturesForAsset);
							
							ObjectNode requestNodeGetSignaturesForAsset = mapper.createObjectNode();
							requestNodeGetSignaturesForAsset.put("jsonrpc", "2.0");
							requestNodeGetSignaturesForAsset.put("id", 1);
							requestNodeGetSignaturesForAsset.put("method", "getSignaturesForAsset");
							requestNodeGetSignaturesForAsset.set("params", paramsNodeGetSignaturesForAsset);
							
							JsonNode resultNodeGetSignaturesForAsset = this.requestHttp(requestUrl, requestNodeGetSignaturesForAsset);
							// {
							//   "jsonrpc": "2.0",
							//   "result": {
							//     "total": 3,
							//     "limit": 1000,
							//     "page": 1,
							//     "items": [
							//       [
							//         "5nLi8m72bU6PBcz4Xrk23P6KTGy9ufF92kZiQXjTv9ELgkUxrNaiCGhMF4vh6RAcisw9DEQWJt9ogM3G2uCuwwV7",
							//         "MintToCollectionV1"
							//       ],
							//       [
							//         "323Ag4J69gagBt3neUvajNauMydiXZTmXYSfdK5swWcK1iwCUypcXv45UFcy5PTt136G9gtQ45oyPJRs1f2zFZ3v",
							//         "Transfer"
							//       ],
							//       [
							//         "3TbybyYRtNjVMhhahTNbd4bbpiEacZn2qkwtH7ByL7tCHmwi2g4YapPidSRGs1gjaseKbs7RjNmUKWmU6xbf3wUT",
							//         "Transfer"
							//       ]
							//     ]
							//   },
							//   "id": 1
							// }
							
							// item(트랜잭션 정보) 추출
							JsonNode items = resultNodeGetSignaturesForAsset.path("items");
							
							// items 배열을 순회
							for (JsonNode item : items) {
								// 각 배열의 두 번째 값("Transfer" 여부 확인)
								if (item.isArray() && item.size() > 1) {
									String value = item.get(1).asText();
									if ("Transfer".equals(value)) { // Transfer 인 경우만 추가
										signatureList.add(item.get(0).asText());
									}
								}
							}
							
							// 결과가 1000 보다 적다면 더 이상 데이터가 없다는 의미
							hasMoreDataGetSignaturesForAsset = (resultNodeGetSignaturesForAsset.path("result").path("total").asInt() == 1000);
							pageGetSignaturesForAsset++;
						}
						
						// signatureList를 역순으로 조회해서 제일 마지막 Transfer의 시그니처가 상장 트랜잭션
						String transactionSignature = signatureList.get(signatureList.size() - 1);
						////////////////////////////////////////////////////////////////////////////////////
						// ■■■■■■■■■■ 2. 1의 transactionSignature로 getTransaction 결과에서 상장 이전 owner 찾기 ■■■■■■■■■■
						// 요청 본문 설정
						ArrayNode arrayNode = mapper.createArrayNode();
						arrayNode.add(transactionSignature);
						
						ObjectNode requestNodeGetTransaction = mapper.createObjectNode();
						requestNodeGetTransaction.put("jsonrpc", "2.0");
						requestNodeGetTransaction.put("id", 1);
						requestNodeGetTransaction.put("method", "getTransaction");
						requestNodeGetTransaction.set("params", arrayNode);
						
						JsonNode resultNodeGetTransaction = this.requestHttp(requestUrl, requestNodeGetTransaction);
						
						JsonNode JsonNodeAccountKeys = resultNodeGetTransaction.path("transaction").path("message").path("accountKeys");
						String WalletAddress = JsonNodeAccountKeys.get(0).asText();
						
						// 상장 전 지갑주소를 owner_address로 설정
						snapshotVo.setOwner_address(WalletAddress);
					}
					////////////////////////////////////////////////////////////////////////////////////
					
					
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
				hasMoreData = resultArray.size() == 1000;
				page++;
			}
		}
	} catch (Exception e) {
		e.printStackTrace();
		commonVo.setResultCd("FAIL");
		commonVo.setResultMsg(e.toString());
	}
		return commonVo;
	}
	
	/**
	 * http request
	 * 
	 * @param String url
	 * @param ObjectNode requestNode
	 * 
	 * @return commonVo
	 * @throws Exception
	 */
	public JsonNode requestHttp(String url, ObjectNode requestNode) throws Exception {
		try (CloseableHttpClient client = HttpClients.createDefault()) {
			ObjectMapper mapper = new ObjectMapper();
			
			HttpPost httpPost = new HttpPost(url);
			StringEntity entity = new StringEntity(requestNode.toString(), "UTF-8");
			httpPost.setEntity(entity);
			
			// 헤더 설정
			httpPost.addHeader("Content-Type", "application/json");
			
			// 요청 실행
			HttpResponse response = client.execute(httpPost);
			int responseCode = response.getStatusLine().getStatusCode();
			
			if (responseCode != 200) {
				logger.error("■■■■■■■ [requestHttp] responseCode: " + responseCode);
				return null;
			}
			
			// JSON 응답 처리
			JsonNode returnNode = mapper.readTree(response.getEntity().getContent());
			JsonNode resultJsonNode = returnNode.path("result");
			
			return resultJsonNode;
		}
	}
}
