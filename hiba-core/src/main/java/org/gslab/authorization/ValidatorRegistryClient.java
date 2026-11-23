package org.gslab.authorization;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.gslab.tpm.blockchain.Web3BlockchainService;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;

/**
 * 讀 ValidatorRegistry 合約（唯讀，不需要私鑰）。
 * <p>
 * 結果快取 {@link #CACHE_MS}；鏈連不上時沿用最後一次成功讀到的名單（最多 {@link #STALE_LIMIT_MS}），
 * 讓鏈短暫離線時授權仍可運作。
 * </p>
 */
@Slf4j
public class ValidatorRegistryClient {

    static final long CACHE_MS = 30_000;
    static final long STALE_LIMIT_MS = 24L * 3600 * 1000;

    public record Validator(String ekFingerprint, String domainId, String endpoint) {}

    /** 某個版本的有效 Validator 名單 */
    public record ValidatorSet(long version, int threshold, Map<String, Validator> active, long fetchedAt) {
        public boolean contains(String ek) {
            return ek != null && active.containsKey(ek);
        }

        public int size() {
            return active.size();
        }
    }

    private final Web3BlockchainService chain;
    private final String contractAddress;
    private volatile ValidatorSet cached;

    public ValidatorRegistryClient(Web3BlockchainService chain, String contractAddress) {
        this.chain = chain;
        this.contractAddress = contractAddress;
    }

    /** 取得目前名單；從沒成功讀過、或快取太舊又讀不到時為 empty */
    public Optional<ValidatorSet> current() {
        ValidatorSet c = cached;
        long now = System.currentTimeMillis();
        if (c != null && now - c.fetchedAt() < CACHE_MS) {
            return Optional.of(c);
        }
        Optional<ValidatorSet> fresh = fetch();
        if (fresh.isPresent()) {
            ValidatorSet f = fresh.get();
            if (c == null || c.version() != f.version()) {
                log.info(
                        "[Auth] Validator set version {} loaded: {} active, threshold {}",
                        f.version(), f.size(), f.threshold());
            }
            cached = f;
            return fresh;
        }
        if (c != null && now - c.fetchedAt() < STALE_LIMIT_MS) {
            log.debug("[Auth] ValidatorRegistry unreachable, using cached version {}", c.version());
            return Optional.of(c);
        }
        return Optional.empty();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Optional<ValidatorSet> fetch() {
        Function fn = new Function(
                "getValidatorSet",
                List.of(),
                List.<TypeReference<?>>of(
                        new TypeReference<Uint256>() {},
                        new TypeReference<Uint256>() {},
                        new TypeReference<DynamicArray<Utf8String>>() {},
                        new TypeReference<DynamicArray<Utf8String>>() {},
                        new TypeReference<DynamicArray<Utf8String>>() {}));
        Optional<List<Type>> out = chain.callView(contractAddress, fn);
        if (out.isEmpty() || out.get().size() < 5) {
            return Optional.empty();
        }
        try {
            List<Type> r = out.get();
            long version = ((BigInteger) r.get(0).getValue()).longValue();
            int threshold = ((BigInteger) r.get(1).getValue()).intValue();
            List<String> eks = strings((List<Utf8String>) r.get(2).getValue());
            List<String> domains = strings((List<Utf8String>) r.get(3).getValue());
            List<String> endpoints = strings((List<Utf8String>) r.get(4).getValue());
            Map<String, Validator> active = new LinkedHashMap<>();
            for (int i = 0; i < eks.size(); i++) {
                active.put(
                        eks.get(i),
                        new Validator(
                                eks.get(i),
                                i < domains.size() ? domains.get(i) : "",
                                i < endpoints.size() ? endpoints.get(i) : ""));
            }
            return Optional.of(
                    new ValidatorSet(version, threshold, Collections.unmodifiableMap(active), System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("[Auth] Cannot decode getValidatorSet() from {}: {}", contractAddress, e.getMessage());
            return Optional.empty();
        }
    }

    private static List<String> strings(List<Utf8String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (Utf8String s : in) {
            out.add(s.getValue());
        }
        return out;
    }
}
