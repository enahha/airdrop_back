SELECT 
    owner_address
    , nft_name
    , rarity
    , (SELECT COUNT(*) FROM snapshot X WHERE X.mst_seq = 1 AND X.owner_address = A.owner_address) total_nft_count
FROM 
    snapshot A
WHERE mst_seq = 1
GROUP BY 
    owner_address, 
    nft_name, 
    rarity
ORDER BY
	total_nft_count DESC, owner_address, CAST(SUBSTRING_INDEX(nft_name, '#', -1) AS UNSIGNED) ASC;
    
    
    
SELECT 
    owner_address
    , (SELECT COUNT(*) FROM snapshot X WHERE X.mst_seq = 3 AND X.owner_address = A.owner_address) total_nft_count
FROM 
    snapshot A
WHERE mst_seq = 3
GROUP BY 
    owner_address
ORDER BY
	total_nft_count DESC;
	
