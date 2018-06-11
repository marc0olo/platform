package io.fundrequest.core.request.fund;

import io.fundrequest.core.contract.service.FundRequestContractsService;
import io.fundrequest.core.infrastructure.exception.ResourceNotFoundException;
import io.fundrequest.core.infrastructure.mapping.Mappers;
import io.fundrequest.core.request.domain.Request;
import io.fundrequest.core.request.domain.RequestStatus;
import io.fundrequest.core.request.fiat.FiatService;
import io.fundrequest.core.request.fund.command.FundsAddedCommand;
import io.fundrequest.core.request.fund.domain.Fund;
import io.fundrequest.core.request.fund.domain.PendingFund;
import io.fundrequest.core.request.fund.dto.FundDto;
import io.fundrequest.core.request.fund.dto.FunderDto;
import io.fundrequest.core.request.fund.dto.FundersDto;
import io.fundrequest.core.request.fund.dto.TotalFundDto;
import io.fundrequest.core.request.fund.event.RequestFundedEvent;
import io.fundrequest.core.request.fund.infrastructure.FundRepository;
import io.fundrequest.core.request.fund.infrastructure.PendingFundRepository;
import io.fundrequest.core.request.infrastructure.RequestRepository;
import io.fundrequest.core.token.TokenInfoService;
import io.fundrequest.core.token.dto.TokenInfoDto;
import io.fundrequest.platform.profile.profile.ProfileService;
import io.fundrequest.platform.profile.profile.dto.UserProfile;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.LongStream;

import static io.fundrequest.core.web3j.EthUtil.fromWei;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.reducing;
import static java.util.stream.Collectors.toList;

@Service
class FundServiceImpl implements FundService {

    private static final String FND_TOKEN_SYMBOL = "FND";

    private FundRepository fundRepository;
    private PendingFundRepository pendingFundRepository;
    private RequestRepository requestRepository;
    private Mappers mappers;
    private ApplicationEventPublisher eventPublisher;
    private CacheManager cacheManager;
    private TokenInfoService tokenInfoService;
    private FundRequestContractsService fundRequestContractsService;
    private ProfileService profileService;
    private FiatService fiatService;

    @Autowired
    public FundServiceImpl(FundRepository fundRepository,
                           PendingFundRepository pendingFundRepository,
                           RequestRepository requestRepository,
                           Mappers mappers,
                           ApplicationEventPublisher eventPublisher,
                           CacheManager cacheManager,
                           TokenInfoService tokenInfoService,
                           FundRequestContractsService fundRequestContractsService,
                           ProfileService profileService,
                           FiatService fiatService) {
        this.fundRepository = fundRepository;
        this.pendingFundRepository = pendingFundRepository;
        this.requestRepository = requestRepository;
        this.mappers = mappers;
        this.eventPublisher = eventPublisher;
        this.cacheManager = cacheManager;
        this.tokenInfoService = tokenInfoService;
        this.fundRequestContractsService = fundRequestContractsService;
        this.profileService = profileService;
        this.fiatService = fiatService;
    }

    @Transactional(readOnly = true)
    @Override
    public List<FundDto> findAll() {
        return mappers.mapList(Fund.class, FundDto.class, fundRepository.findAll());
    }

    @Transactional(readOnly = true)
    @Override
    public List<FundDto> findAll(Iterable<Long> ids) {
        return mappers.mapList(Fund.class, FundDto.class, fundRepository.findAll(ids));
    }


    @Override
    @Transactional(readOnly = true)
    public FundDto findOne(Long id) {
        return mappers.map(Fund.class, FundDto.class, fundRepository.findOne(id).orElseThrow(ResourceNotFoundException::new));
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = "funds", key = "#requestId")
    public List<TotalFundDto> getTotalFundsForRequest(Long requestId) {

        final Optional<Request> one = requestRepository.findOne(requestId);
        if (one.isPresent()) {
            try {
                if (one.get().getStatus() == RequestStatus.CLAIMED) {
                    return getFromClaimRepository(one.get());
                } else {
                    return getFromFundRepository(one.get());
                }
            } catch (final Exception ex) {
                return Collections.emptyList();
            }
        } else {
            return Collections.emptyList();
        }
    }

    private List<TotalFundDto> getFromClaimRepository(final Request request) {
        final Long tokenCount = fundRequestContractsService.claimRepository()
                                                           .getTokenCount(request.getIssueInformation().getPlatform().name(),
                                                                          request.getIssueInformation().getPlatformId());

        return LongStream.range(0, tokenCount)
                         .mapToObj(x -> fundRequestContractsService.claimRepository()
                                                                   .getTokenByIndex(request.getIssueInformation().getPlatform().name(),
                                                                                    request.getIssueInformation().getPlatformId(),
                                                                                    x))
                         .filter(Optional::isPresent)
                         .map(Optional::get)
                         .map(getTotalClaimFundDto(request)).collect(toList());
    }

    private List<TotalFundDto> getFromFundRepository(final Request request) {
        final Long fundedTokenCount = fundRequestContractsService.fundRepository()
                                                                 .getFundedTokenCount(request.getIssueInformation().getPlatform().name(),
                                                                                      request.getIssueInformation().getPlatformId());
        return LongStream.range(0, fundedTokenCount)
                         .mapToObj(x -> fundRequestContractsService.fundRepository()
                                                                   .getFundedToken(request.getIssueInformation().getPlatform().name(),
                                                                                   request.getIssueInformation().getPlatformId(),
                                                                                   x)).filter(Optional::isPresent)
                         .map(Optional::get)
                         .map(getTotalFundDto(request)).collect(toList());
    }




    @Override
    @Transactional(readOnly = true)
    public FundersDto getFundedBy(final Principal principal, final Long requestId) {
        final List<FunderDto> list = groupByFunder(fundRepository.findByRequestId(requestId)
                                                                 .stream()
                                                                 .map(r -> this.mapToFunderDto(principal == null ? null : profileService.getUserProfile(principal.getName()), r))
                                                                 .filter(Objects::nonNull)
                                                                 .collect(toList()));
        enrichFundsWithZeroValues(list);
        final TotalFundDto fndFunds = totalFunds(list, FunderDto::getFndFunds);
        final TotalFundDto otherFunds = totalFunds(list, FunderDto::getOtherFunds);
        return FundersDto.builder()
                         .funders(list)
                         .fndFunds(fndFunds)
                         .otherFunds(otherFunds)
                         .usdFunds(fiatService.getUsdPrice(fndFunds, otherFunds))
                         .build();
    }

    private List<FunderDto> groupByFunder(final List<FunderDto> list) {
        return list.stream()
                   .collect(groupingBy(FunderDto::getFunder,
                                       reducing((a1, b1) -> {
                                           if (a1 == null && b1 == null) {
                                               return null;
                                           } else if (a1 == null) {
                                               return b1;
                                           } else if (b1 == null) {
                                               return a1;
                                           }
                                           a1.setFndFunds(mergeFunds(a1.getFndFunds(), b1.getFndFunds()));
                                           a1.setOtherFunds(mergeFunds(a1.getOtherFunds(), b1.getOtherFunds()));
                                           return a1;
                                       })))
                   .values()
                   .stream()
                   .filter(Optional::isPresent)
                   .map(Optional::get)
                   .collect(toList());
    }

    private TotalFundDto mergeFunds(TotalFundDto bFunds, TotalFundDto aFunds) {
        if (bFunds != null) {
            if (aFunds == null) {
                return bFunds;
            } else {
                aFunds.setTotalAmount(aFunds.getTotalAmount().add(bFunds.getTotalAmount()));
            }
        }

        return aFunds;
    }

    private void enrichFundsWithZeroValues(final List<FunderDto> list) {
        TotalFundDto fndNonEmpty = null;
        TotalFundDto otherNonEmpty = null;
        for (FunderDto f : list) {
            if (f.getFndFunds() != null) {
                fndNonEmpty = f.getFndFunds();
            }
            if (f.getOtherFunds() != null) {
                otherNonEmpty = f.getOtherFunds();
            }
        }

        for (FunderDto f : list) {
            if (fndNonEmpty != null && f.getFndFunds() == null) {
                f.setFndFunds(TotalFundDto.builder().tokenSymbol(fndNonEmpty.getTokenSymbol()).tokenAddress(fndNonEmpty.getTokenAddress()).totalAmount(BigDecimal.ZERO).build());
            }
            if (otherNonEmpty != null && f.getOtherFunds() == null) {
                f.setOtherFunds(TotalFundDto.builder()
                                            .tokenSymbol(otherNonEmpty.getTokenSymbol())
                                            .tokenAddress(otherNonEmpty.getTokenAddress())
                                            .totalAmount(BigDecimal.ZERO)
                                            .build());
            }
        }
    }

    private TotalFundDto totalFunds(final List<FunderDto> funds, final Function<FunderDto, TotalFundDto> getFundsFunction) {
        if (funds.isEmpty()) {
            return null;
        }
        BigDecimal totalValue = funds.stream()
                                     .map(getFundsFunction)
                                     .filter(Objects::nonNull)
                                     .map(TotalFundDto::getTotalAmount)
                                     .reduce(BigDecimal.ZERO, BigDecimal::add);
        return funds.stream()
                    .map(getFundsFunction)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .map(f -> TotalFundDto.builder()
                                          .tokenSymbol(f.getTokenSymbol())
                                          .tokenAddress(f.getTokenAddress())
                                          .totalAmount(totalValue)
                                          .build())
                    .orElse(null);
    }

    private FunderDto mapToFunderDto(final UserProfile loggedInUserProfile, final Fund fund) {
        final TotalFundDto totalFundDto = createTotalFund(fund.getToken(), fund.getAmountInWei());
        final String funderNameOrAddress = StringUtils.isNotBlank(fund.getFunderUserId())
                                           ? profileService.getUserProfile(fund.getFunderUserId()).getName()
                                           : fund.getFunderAddress();
        final boolean isFundedByLoggedInUser = isFundedByLoggedInUser(loggedInUserProfile, fund);
        return totalFundDto == null ? null : FunderDto.builder()
                                                      .funder(funderNameOrAddress)
                                                      .funderAddress(fund.getFunderAddress())
                                                      .fndFunds(getFndFunds(totalFundDto))
                                                      .otherFunds(getOtherFunds(totalFundDto))
                                                      .isLoggedInUser(isFundedByLoggedInUser)
                                                      .build();
    }

    private boolean isFundedByLoggedInUser(final UserProfile loggedInUserProfile, final Fund fund) {
        return loggedInUserProfile != null
               && (loggedInUserProfile.getId().equals(fund.getFunderUserId()) || fund.getFunderAddress().equalsIgnoreCase(loggedInUserProfile.getEtherAddress()));
    }

    private TotalFundDto getFndFunds(final TotalFundDto totalFundDto) {
        return hasFNDTokenSymbol(totalFundDto) ? totalFundDto : null;
    }

    private TotalFundDto getOtherFunds(final TotalFundDto totalFundDto) {
        return !hasFNDTokenSymbol(totalFundDto) ? totalFundDto : null;
    }

    private boolean hasFNDTokenSymbol(TotalFundDto totalFundDto) {
        return FND_TOKEN_SYMBOL.equalsIgnoreCase(totalFundDto.getTokenSymbol());
    }

    @Override
    @CacheEvict(value = "funds", key = "#requestId")
    public void clearTotalFundsCache(Long requestId) {
        // Intentionally blank
    }

    private Function<String, TotalFundDto> getTotalClaimFundDto(final Request request) {
        return tokenAddress -> {
            final BigDecimal rawBalance = new BigDecimal(fundRequestContractsService.claimRepository()
                                                                                    .getAmountByToken(request.getIssueInformation().getPlatform().name(),
                                                                                                      request.getIssueInformation().getPlatformId(),
                                                                                                      tokenAddress));
            return createTotalFund(tokenAddress, rawBalance);
        };
    }

    private Function<String, TotalFundDto> getTotalFundDto(final Request request) {
        return tokenAddress -> {
            final BigDecimal rawBalance = new BigDecimal(fundRequestContractsService.fundRepository()
                                                                                    .balance(request.getIssueInformation().getPlatform().name(),
                                                                                             request.getIssueInformation().getPlatformId(),
                                                                                             tokenAddress));
            return createTotalFund(tokenAddress, rawBalance);
        };
    }

    private TotalFundDto createTotalFund(String tokenAddress, BigDecimal rawBalance) {
        final TokenInfoDto tokenInfo = tokenInfoService.getTokenInfo(tokenAddress);
        return tokenInfo == null
               ? null
               : TotalFundDto.builder()
                             .tokenAddress(tokenInfo.getAddress())
                             .tokenSymbol(tokenInfo.getSymbol())
                             .totalAmount(fromWei(rawBalance, tokenInfo.getDecimals()))
                             .build();
    }

    @Override
    @Transactional
    public void addFunds(final FundsAddedCommand command) {
        final Fund.FundBuilder fundBuilder = Fund.builder()
                                                 .amountInWei(command.getAmountInWei())
                                                 .requestId(command.getRequestId())
                                                 .token(command.getToken())
                                                 .timestamp(command.getTimestamp())
                                                 .funderAddress(command.getFunderAddress())
                                                 .blockchainEventId(command.getBlockchainEventId());
        final Optional<PendingFund> pendingFund = pendingFundRepository.findByTransactionHash(command.getTransactionHash());
        if (pendingFund.isPresent()) {
            fundBuilder.funderUserId(pendingFund.get().getUserId());
        }
        final Fund fund = fundRepository.saveAndFlush(fundBuilder.build());
        cacheManager.getCache("funds").evict(fund.getRequestId());

        eventPublisher.publishEvent(RequestFundedEvent.builder()
                                                      .fundDto(mappers.map(Fund.class, FundDto.class, fund))
                                                      .requestId(command.getRequestId())
                                                      .timestamp(command.getTimestamp())
                                                      .build());
    }
}
