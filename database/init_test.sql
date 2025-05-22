SELECT * FROM test2.airdrop_log;

-- 에어드랍 로그 테이블 초기화
update test2.airdrop_log set status = 0, new_nft_id = null, new_mint_account_key = null, new_mint_signature = null, mod_time = null;

SELECT * FROM test2.nft_info;

-- NFT 정보 테이블 초기화
update test2.nft_info set status = 0, mod_time = null;