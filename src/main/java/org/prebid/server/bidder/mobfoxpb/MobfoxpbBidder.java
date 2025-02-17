package org.prebid.server.bidder.mobfoxpb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.Bid;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import io.vertx.core.http.HttpMethod;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpCall;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.json.DecodeException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.mobfoxpb.ExtImpMobfoxpb;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.util.HttpUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class MobfoxpbBidder implements Bidder<BidRequest> {

    private static final String ROUTE_RTB = "rtb";
    private static final String METHOD_RTB = "req";
    private static final String ROUTE_NATIVE = "o";
    private static final String METHOD_NATIVE = "ortb";
    private static final String URL_KEY_MACROS = "__key__";
    private static final String URL_ROUTE_MACROS = "__route__";
    private static final String URL_METHOD_MACROS = "__method__";
    private final String endpointUrl;
    private final JacksonMapper mapper;

    private static final TypeReference<ExtPrebid<?, ExtImpMobfoxpb>> MOBFOXPB_EXT_TYPE_REFERENCE =
            new TypeReference<ExtPrebid<?, ExtImpMobfoxpb>>() {
            };

    public MobfoxpbBidder(String endpoint, JacksonMapper mapper) {
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpoint));
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public final Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest bidRequest) {
        final BidRequest outgoingRequest;
        final String uri;
        try {
            final Imp firstImp = bidRequest.getImp().get(0);
            final ExtImpMobfoxpb impExt = parseImpExt(firstImp);

            uri = buildUri(impExt.getKey());
            outgoingRequest = bidRequest.toBuilder()
                    .imp(Collections.singletonList(firstImp))
                    .build();
        } catch (PreBidException e) {
            return Result.withError(BidderError.badInput(e.getMessage()));
        }

        return Result.withValue(HttpRequest.<BidRequest>builder()
                .method(HttpMethod.POST)
                .uri(uri)
                .body(mapper.encodeToBytes(outgoingRequest))
                .headers(HttpUtil.headers())
                .payload(outgoingRequest)
                .build());
    }

    private ExtImpMobfoxpb parseImpExt(Imp imp) {
        final ExtImpMobfoxpb extImpMobfoxpb;
        try {
            extImpMobfoxpb = mapper.mapper().convertValue(imp.getExt(), MOBFOXPB_EXT_TYPE_REFERENCE).getBidder();
        } catch (IllegalArgumentException e) {
            throw new PreBidException(e.getMessage());
        }
        if (StringUtils.isEmpty(extImpMobfoxpb.getKey()) && StringUtils.isEmpty(extImpMobfoxpb.getTagId())) {
            throw new PreBidException("Invalid or non existing key and tagId, atleast one should be present");
        }
        return extImpMobfoxpb;
    }

    private String buildUri(String key) {
        final String route;
        final String method;
        String uri = endpointUrl;
        if (StringUtils.isNotEmpty(key)) {
            route = ROUTE_RTB;
            method = METHOD_RTB;
            uri = uri.replace(URL_KEY_MACROS, key);
        } else {
            route = ROUTE_NATIVE;
            method = METHOD_NATIVE;
        }

        return uri.replace(URL_ROUTE_MACROS, route).replace(URL_METHOD_MACROS, method);
    }

    @Override
    public Result<List<BidderBid>> makeBids(HttpCall<BidRequest> httpCall, BidRequest bidRequest) {
        try {
            final BidResponse bidResponse = mapper.decodeValue(httpCall.getResponse().getBody(), BidResponse.class);
            final BidRequest payload = httpCall.getRequest().getPayload();
            return extractBids(bidResponse, payload.getImp());
        } catch (DecodeException e) {
            return Result.withError(BidderError.badServerResponse(e.getMessage()));
        }
    }

    private static Result<List<BidderBid>> extractBids(BidResponse bidResponse, List<Imp> imps) {
        if (bidResponse == null || CollectionUtils.isEmpty(bidResponse.getSeatbid())) {
            return Result.of(Collections.emptyList(), Collections.emptyList());
        }

        final List<BidderError> errors = new ArrayList<>();

        final List<BidderBid> bidderBids = bidResponse.getSeatbid().stream()
                .filter(Objects::nonNull)
                .map(SeatBid::getBid)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(bid -> bidFromResponse(imps, bid, bidResponse.getCur(), errors))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return Result.of(bidderBids, errors);
    }

    private static BidderBid bidFromResponse(List<Imp> imps, Bid bid, String currency, List<BidderError> errors) {
        try {
            return BidderBid.of(bid, getBidType(bid.getImpid(), imps), currency);
        } catch (PreBidException e) {
            errors.add(BidderError.badInput(e.getMessage()));
            return null;
        }
    }

    private static BidType getBidType(String impId, List<Imp> imps) {
        for (Imp imp : imps) {
            if (imp.getId().equals(impId)) {
                if (imp.getBanner() != null) {
                    return BidType.banner;
                } else if (imp.getVideo() != null) {
                    return BidType.video;
                } else if (imp.getXNative() != null) {
                    return BidType.xNative;
                }
            }
        }
        throw new PreBidException(String.format("Failed to find impression \"%s\"", impId));
    }
}
