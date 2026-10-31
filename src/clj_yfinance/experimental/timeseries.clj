(ns clj-yfinance.experimental.timeseries
  "EXPERIMENTAL: financial statements from Yahoo's fundamentals-timeseries
   endpoint.

   This is the endpoint Yahoo's own financials pages use. Unlike the
   quoteSummary statement modules (see fetch-financials in
   clj-yfinance.experimental.fundamentals, which Yahoo has cut down to a few
   fields), it returns the full income statement, balance sheet and cash flow
   statement. It needs no cookie or crumb.

   - fetch-statements / fetch-statements* - statement line items as tidy rows
   - fetch-timeseries / fetch-timeseries* - raw access by Yahoo type string
     (e.g. \"quarterlyTotalRevenue\")
   - statement-fields, field->keyword      - the field catalog

   Data notes (Yahoo's behaviour, not this library's):
   - Typical depth is 4 annual, 5 quarterly and a few trailing (TTM) points.
   - Period ends are normalised to month ends: a fiscal quarter ending
     Saturday 2025-09-27 is reported as \"2025-09-30\".
   - Annual and quarterly per-share values are restated to the current share
     basis (NVDA's FY2024 diluted EPS comes back as 1.19, not the 11.93
     reported before its 2024 10:1 split). Trailing points are not always
     restated.
   - :currency is the reporting currency, which can differ from the trading
     currency (0005.HK reports in USD).
   - There is no trailing balance sheet.

   WARNING: Yahoo does not document this endpoint and can change it at any time."
  (:require [clj-yfinance.http :as http]
            [clj-yfinance.parse :as parse]
            [charred.api :as charred]
            [clojure.string :as str])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; Field catalog (from python-yfinance's yfinance/const.py fundamentals_keys;
;; no name appears in more than one statement there)
;; ---------------------------------------------------------------------------

(def income-statement-fields
  "Income statement fields (Yahoo field names, 103), in python-yfinance's
   fundamentals_keys order."
  ["TaxEffectOfUnusualItems" "TaxRateForCalcs" "NormalizedEBITDA"
   "NormalizedDilutedEPS" "NormalizedBasicEPS" "TotalUnusualItems"
   "TotalUnusualItemsExcludingGoodwill"
   "NetIncomeFromContinuingOperationNetMinorityInterest"
   "ReconciledDepreciation" "ReconciledCostOfRevenue" "EBITDA" "EBIT"
   "NetInterestIncome" "InterestExpense" "InterestIncome"
   "ContinuingAndDiscontinuedDilutedEPS" "ContinuingAndDiscontinuedBasicEPS"
   "NormalizedIncome" "NetIncomeFromContinuingAndDiscontinuedOperation"
   "TotalExpenses" "RentExpenseSupplemental" "ReportedNormalizedDilutedEPS"
   "ReportedNormalizedBasicEPS" "TotalOperatingIncomeAsReported"
   "DividendPerShare" "DilutedAverageShares" "BasicAverageShares" "DilutedEPS"
   "DilutedEPSOtherGainsLosses" "TaxLossCarryforwardDilutedEPS"
   "DilutedAccountingChange" "DilutedExtraordinary"
   "DilutedDiscontinuousOperations" "DilutedContinuousOperations" "BasicEPS"
   "BasicEPSOtherGainsLosses" "TaxLossCarryforwardBasicEPS"
   "BasicAccountingChange" "BasicExtraordinary" "BasicDiscontinuousOperations"
   "BasicContinuousOperations" "DilutedNIAvailtoComStockholders"
   "AverageDilutionEarnings" "NetIncomeCommonStockholders"
   "OtherunderPreferredStockDividend" "PreferredStockDividends" "NetIncome"
   "MinorityInterests" "NetIncomeIncludingNoncontrollingInterests"
   "NetIncomeFromTaxLossCarryforward" "NetIncomeExtraordinary"
   "NetIncomeDiscontinuousOperations" "NetIncomeContinuousOperations"
   "EarningsFromEquityInterestNetOfTax" "TaxProvision" "PretaxIncome"
   "OtherIncomeExpense" "OtherNonOperatingIncomeExpenses"
   "SpecialIncomeCharges" "GainOnSaleOfPPE" "GainOnSaleOfBusiness"
   "OtherSpecialCharges" "WriteOff" "ImpairmentOfCapitalAssets"
   "RestructuringAndMergernAcquisition" "SecuritiesAmortization"
   "EarningsFromEquityInterest" "GainOnSaleOfSecurity"
   "NetNonOperatingInterestIncomeExpense" "TotalOtherFinanceCost"
   "InterestExpenseNonOperating" "InterestIncomeNonOperating"
   "OperatingIncome" "OperatingExpense" "OtherOperatingExpenses" "OtherTaxes"
   "ProvisionForDoubtfulAccounts"
   "DepreciationAmortizationDepletionIncomeStatement"
   "DepletionIncomeStatement" "DepreciationAndAmortizationInIncomeStatement"
   "Amortization" "AmortizationOfIntangiblesIncomeStatement"
   "DepreciationIncomeStatement" "ResearchAndDevelopment"
   "SellingGeneralAndAdministration" "SellingAndMarketingExpense"
   "GeneralAndAdministrativeExpense" "OtherGandA" "InsuranceAndClaims"
   "RentAndLandingFees" "SalariesAndWages" "GrossProfit" "CostOfRevenue"
   "TotalRevenue" "ExciseTaxes" "OperatingRevenue" "LossAdjustmentExpense"
   "NetPolicyholderBenefitsAndClaims" "PolicyholderBenefitsGross"
   "PolicyholderBenefitsCeded" "OccupancyAndEquipment"
   "ProfessionalExpenseAndContractServicesExpense" "OtherNonInterestExpense"])

(def balance-sheet-fields
  "Balance sheet fields (Yahoo field names, 149), in python-yfinance's
   fundamentals_keys order."
  ["TreasurySharesNumber" "PreferredSharesNumber" "OrdinarySharesNumber"
   "ShareIssued" "NetDebt" "TotalDebt" "TangibleBookValue" "InvestedCapital"
   "WorkingCapital" "NetTangibleAssets" "CapitalLeaseObligations"
   "CommonStockEquity" "PreferredStockEquity" "TotalCapitalization"
   "TotalEquityGrossMinorityInterest" "MinorityInterest" "StockholdersEquity"
   "OtherEquityInterest" "GainsLossesNotAffectingRetainedEarnings"
   "OtherEquityAdjustments" "FixedAssetsRevaluationReserve"
   "ForeignCurrencyTranslationAdjustments" "MinimumPensionLiabilities"
   "UnrealizedGainLoss" "TreasuryStock" "RetainedEarnings"
   "AdditionalPaidInCapital" "CapitalStock" "OtherCapitalStock" "CommonStock"
   "PreferredStock" "TotalPartnershipCapital" "GeneralPartnershipCapital"
   "LimitedPartnershipCapital" "TotalLiabilitiesNetMinorityInterest"
   "TotalNonCurrentLiabilitiesNetMinorityInterest"
   "OtherNonCurrentLiabilities" "LiabilitiesHeldforSaleNonCurrent"
   "RestrictedCommonStock" "PreferredSecuritiesOutsideStockEquity"
   "DerivativeProductLiabilities" "EmployeeBenefits"
   "NonCurrentPensionAndOtherPostretirementBenefitPlans"
   "NonCurrentAccruedExpenses" "DuetoRelatedPartiesNonCurrent"
   "TradeandOtherPayablesNonCurrent" "NonCurrentDeferredLiabilities"
   "NonCurrentDeferredRevenue" "NonCurrentDeferredTaxesLiabilities"
   "LongTermDebtAndCapitalLeaseObligation" "LongTermCapitalLeaseObligation"
   "LongTermDebt" "LongTermProvisions" "CurrentLiabilities"
   "OtherCurrentLiabilities" "CurrentDeferredLiabilities"
   "CurrentDeferredRevenue" "CurrentDeferredTaxesLiabilities"
   "CurrentDebtAndCapitalLeaseObligation" "CurrentCapitalLeaseObligation"
   "CurrentDebt" "OtherCurrentBorrowings" "LineOfCredit" "CommercialPaper"
   "CurrentNotesPayable" "PensionandOtherPostRetirementBenefitPlansCurrent"
   "CurrentProvisions" "PayablesAndAccruedExpenses" "CurrentAccruedExpenses"
   "InterestPayable" "Payables" "OtherPayable" "DuetoRelatedPartiesCurrent"
   "DividendsPayable" "TotalTaxPayable" "IncomeTaxPayable" "AccountsPayable"
   "TotalAssets" "TotalNonCurrentAssets" "OtherNonCurrentAssets"
   "DefinedPensionBenefit" "NonCurrentPrepaidAssets"
   "NonCurrentDeferredAssets" "NonCurrentDeferredTaxesAssets"
   "DuefromRelatedPartiesNonCurrent" "NonCurrentNoteReceivables"
   "NonCurrentAccountsReceivable" "FinancialAssets" "InvestmentsAndAdvances"
   "OtherInvestments" "InvestmentinFinancialAssets" "HeldToMaturitySecurities"
   "AvailableForSaleSecurities"
   "FinancialAssetsDesignatedasFairValueThroughProfitorLossTotal"
   "TradingSecurities" "LongTermEquityInvestment"
   "InvestmentsinJointVenturesatCost"
   "InvestmentsInOtherVenturesUnderEquityMethod"
   "InvestmentsinAssociatesatCost" "InvestmentsinSubsidiariesatCost"
   "InvestmentProperties" "GoodwillAndOtherIntangibleAssets"
   "OtherIntangibleAssets" "Goodwill" "NetPPE" "AccumulatedDepreciation"
   "GrossPPE" "Leases" "ConstructionInProgress" "OtherProperties"
   "MachineryFurnitureEquipment" "BuildingsAndImprovements"
   "LandAndImprovements" "Properties" "CurrentAssets" "OtherCurrentAssets"
   "HedgingAssetsCurrent" "AssetsHeldForSaleCurrent" "CurrentDeferredAssets"
   "CurrentDeferredTaxesAssets" "RestrictedCash" "PrepaidAssets" "Inventory"
   "InventoriesAdjustmentsAllowances" "OtherInventories" "FinishedGoods"
   "WorkInProcess" "RawMaterials" "Receivables"
   "ReceivablesAdjustmentsAllowances" "OtherReceivables"
   "DuefromRelatedPartiesCurrent" "TaxesReceivable"
   "AccruedInterestReceivable" "NotesReceivable" "LoansReceivable"
   "AccountsReceivable" "AllowanceForDoubtfulAccountsReceivable"
   "GrossAccountsReceivable" "CashCashEquivalentsAndShortTermInvestments"
   "OtherShortTermInvestments" "CashAndCashEquivalents" "CashEquivalents"
   "CashFinancial" "CashCashEquivalentsAndFederalFundsSold"
   "FixedMaturityInvestments" "EquityInvestments" "NetLoan" "DeferredAssets"])

(def cash-flow-fields
  "Cash flow statement fields (Yahoo field names, 123), in python-yfinance's
   fundamentals_keys order."
  ["ForeignSales" "DomesticSales" "AdjustedGeographySegmentData"
   "FreeCashFlow" "RepurchaseOfCapitalStock" "RepaymentOfDebt"
   "IssuanceOfDebt" "IssuanceOfCapitalStock" "CapitalExpenditure"
   "InterestPaidSupplementalData" "IncomeTaxPaidSupplementalData"
   "EndCashPosition" "OtherCashAdjustmentOutsideChangeinCash"
   "BeginningCashPosition" "EffectOfExchangeRateChanges" "ChangesInCash"
   "OtherCashAdjustmentInsideChangeinCash" "CashFlowFromDiscontinuedOperation"
   "FinancingCashFlow" "CashFromDiscontinuedFinancingActivities"
   "CashFlowFromContinuingFinancingActivities" "NetOtherFinancingCharges"
   "InterestPaidCFF" "ProceedsFromStockOptionExercised" "CashDividendsPaid"
   "PreferredStockDividendPaid" "CommonStockDividendPaid"
   "NetPreferredStockIssuance" "PreferredStockPayments"
   "PreferredStockIssuance" "NetCommonStockIssuance" "CommonStockPayments"
   "CommonStockIssuance" "NetIssuancePaymentsOfDebt"
   "NetShortTermDebtIssuance" "ShortTermDebtPayments" "ShortTermDebtIssuance"
   "NetLongTermDebtIssuance" "LongTermDebtPayments" "LongTermDebtIssuance"
   "InvestingCashFlow" "CashFromDiscontinuedInvestingActivities"
   "CashFlowFromContinuingInvestingActivities" "NetOtherInvestingChanges"
   "InterestReceivedCFI" "DividendsReceivedCFI" "NetInvestmentPurchaseAndSale"
   "SaleOfInvestment" "PurchaseOfInvestment"
   "NetInvestmentPropertiesPurchaseAndSale" "SaleOfInvestmentProperties"
   "PurchaseOfInvestmentProperties" "NetBusinessPurchaseAndSale"
   "SaleOfBusiness" "PurchaseOfBusiness" "NetIntangiblesPurchaseAndSale"
   "SaleOfIntangibles" "PurchaseOfIntangibles" "NetPPEPurchaseAndSale"
   "SaleOfPPE" "PurchaseOfPPE" "CapitalExpenditureReported"
   "OperatingCashFlow" "CashFromDiscontinuedOperatingActivities"
   "CashFlowFromContinuingOperatingActivities" "TaxesRefundPaid"
   "InterestReceivedCFO" "InterestPaidCFO" "DividendReceivedCFO"
   "DividendPaidCFO" "ChangeInWorkingCapital" "ChangeInOtherWorkingCapital"
   "ChangeInOtherCurrentLiabilities" "ChangeInOtherCurrentAssets"
   "ChangeInPayablesAndAccruedExpense" "ChangeInAccruedExpense"
   "ChangeInInterestPayable" "ChangeInPayable" "ChangeInDividendPayable"
   "ChangeInAccountPayable" "ChangeInTaxPayable" "ChangeInIncomeTaxPayable"
   "ChangeInPrepaidAssets" "ChangeInInventory" "ChangeInReceivables"
   "ChangesInAccountReceivables" "OtherNonCashItems"
   "ExcessTaxBenefitFromStockBasedCompensation" "StockBasedCompensation"
   "UnrealizedGainLossOnInvestmentSecurities" "ProvisionandWriteOffofAssets"
   "AssetImpairmentCharge" "AmortizationOfSecurities" "DeferredTax"
   "DeferredIncomeTax" "DepreciationAmortizationDepletion" "Depletion"
   "DepreciationAndAmortization" "AmortizationCashFlow"
   "AmortizationOfIntangibles" "Depreciation" "OperatingGainsLosses"
   "PensionAndEmployeeBenefitExpense" "EarningsLossesFromEquityInvestments"
   "GainLossOnInvestmentSecurities" "NetForeignCurrencyExchangeGainLoss"
   "GainLossOnSaleOfPPE" "GainLossOnSaleOfBusiness"
   "NetIncomeFromContinuingOperations"
   "CashFlowsfromusedinOperatingActivitiesDirect" "TaxesRefundPaidDirect"
   "InterestReceivedDirect" "InterestPaidDirect" "DividendsReceivedDirect"
   "DividendsPaidDirect" "ClassesofCashPayments"
   "OtherCashPaymentsfromOperatingActivities" "PaymentsonBehalfofEmployees"
   "PaymentstoSuppliersforGoodsandServices"
   "ClassesofCashReceiptsfromOperatingActivities"
   "OtherCashReceiptsfromOperatingActivities" "ReceiptsfromGovernmentGrants"
   "ReceiptsfromCustomers"])

(def statement-fields
  "Yahoo field names by statement."
  {:income-statement income-statement-fields
   :balance-sheet balance-sheet-fields
   :cash-flow cash-flow-fields})

(def ^:private statement-order [:income-statement :balance-sheet :cash-flow])

(def ^:private frequency-order [:annual :quarterly :trailing])

(def ^:private field-statement
  (into {} (for [[statement fields] statement-fields
                 field fields]
             [field statement])))

(defn field->keyword
  "Kebab-case keyword for a Yahoo PascalCase field name. Runs of capitals are
   kept together as one word: \"TotalRevenue\" -> :total-revenue,
   \"DilutedEPS\" -> :diluted-eps, \"NetPPE\" -> :net-ppe,
   \"DilutedNIAvailtoComStockholders\" -> :diluted-ni-availto-com-stockholders.
   Injective over the field catalog."
  [field]
  (-> field
      (str/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      str/lower-case
      keyword))

;; ---------------------------------------------------------------------------
;; Pure parsing
;; ---------------------------------------------------------------------------

(def ^:private no-known-types-message "No timeseries type(s) specified")

(defn- series->rows
  "Rows of one result entry; null entries and entries without a raw value are
   skipped."
  [ticker series]
  (let [type (-> series :meta :type first)]
    (into []
          (keep (fn [entry]
                  (let [raw (-> entry :reportedValue :raw)]
                    (when (number? raw)
                      {:ticker ticker
                       :type type
                       :period-end (:asOfDate entry)
                       :period-type (:periodType entry)
                       :currency (:currencyCode entry)
                       :value (double raw)}))))
          (when type (get series (keyword type))))))

(defn parse-timeseries-body
  "Parse a 200 timeseries response body into {:ok? true :data [rows]} or
   {:ok? false :error {...}}. Rows are {:ticker :type :period-end :period-type
   :currency :value}, in response order. Types Yahoo has no data for are
   simply absent. Pure function for testing."
  [ticker json-string]
  (try
    (let [{:keys [result error]} (:timeseries (charred/read-json json-string {:key-fn keyword}))]
      (cond
        error
        (parse/err {:type :api-error :message (:description error) :yahoo-error error})

        (nil? result)
        (parse/err {:type :api-error :message "No result field in response"})

        :else
        (parse/ok (into [] (mapcat #(series->rows ticker %)) result))))
    (catch Exception ex
      (parse/err {:type :parse-error :message (.getMessage ex)}))))

(defn- no-known-types-body?
  [body]
  (try
    (= no-known-types-message
       (-> (charred/read-json body {:key-fn keyword}) :timeseries :error :description))
    (catch Exception _ false)))

(defn parse-timeseries-response
  "Interpret a timeseries HTTP status and body. Yahoo answers a request whose
   types it knows none of with 404 \"No timeseries type(s) specified\"; that
   becomes {:ok? true :data [] :no-known-types? true}. Any other non-200 is an
   :http-error. Pure function for testing."
  [ticker status body]
  (cond
    (= 200 status) (parse-timeseries-body ticker body)
    (and (= 404 status) (no-known-types-body? body)) (assoc (parse/ok []) :no-known-types? true)
    :else (parse/err {:type :http-error})))

;; ---------------------------------------------------------------------------
;; Option validation
;; ---------------------------------------------------------------------------

(def ^:private default-period1
  "1985-08-23, as python-yfinance uses: earlier than any data Yahoo has."
  493590046)

(def ^:private max-types-per-request 100)

(def ^:private base-urls
  ["https://query2.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/"
   "https://query1.finance.yahoo.com/ws/fundamentals-timeseries/v1/finance/timeseries/"])

(defn- invalid [field value message & {:as extra}]
  (parse/err (merge {:type :invalid-opts :field field :value value :message message} extra)))

(defn- valid-time? [x]
  (or (nil? x) (integer? x) (instance? Instant x)))

(defn- validate-common
  "nil when ticker, opts keys and :start/:end are valid, else an error result."
  [ticker opts allowed-keys]
  (let [unknown (remove allowed-keys (keys opts))
        {:keys [start end]} opts]
    (cond
      (not (and (string? ticker) (not (str/blank? ticker))))
      (invalid :ticker ticker "ticker must be a non-blank string")

      (seq unknown)
      (invalid :opts (vec unknown) (str "Unknown option(s): " (str/join ", " unknown)
                                        ". Allowed: " (str/join ", " (sort allowed-keys)))
               :allowed allowed-keys)

      (not (valid-time? start))
      (invalid :start start "start must be epoch seconds (integer) or java.time.Instant")

      (not (valid-time? end))
      (invalid :end end "end must be epoch seconds (integer) or java.time.Instant")

      (and start end (> (parse/->epoch start) (parse/->epoch end)))
      (invalid :start/end [start end] "start must be <= end"))))

(defn- keyword-subset-error
  "nil when `x` is nil or a non-empty collection of members of `allowed`."
  [field x allowed]
  (when (some? x)
    (cond
      (not (and (coll? x) (not (map? x)) (seq x)))
      (invalid field x (str (name field) " must be a non-empty collection of "
                            (str/join ", " (filter allowed (concat frequency-order statement-order))))
               :allowed allowed)

      (not-every? allowed x)
      (invalid field (vec (remove allowed x))
               (str "Unknown " (name field) ": " (str/join ", " (remove allowed x)))
               :allowed allowed))))

;; ---------------------------------------------------------------------------
;; Fetching
;; ---------------------------------------------------------------------------

(defn- chunk-url [ticker types period1 period2]
  (fn [base-url]
    (str base-url (parse/encode-path-segment ticker) "?"
         (parse/build-query {:symbol ticker
                             :type (str/join "," types)
                             :period1 period1
                             :period2 period2}))))

(defn- fetch-chunk [ticker types period1 period2]
  (http/get-with-failover* (mapv (chunk-url ticker types period1 period2) base-urls)
                           {:ticker ticker
                            :parse-fn #(parse-timeseries-response ticker %1 %2)}))

(defn fetch-timeseries*
  "Fetch raw fundamentals-timeseries data by Yahoo type string (verbose API).

   `types` is a collection of Yahoo types: a frequency prefix (annual,
   quarterly, trailing) plus a field name, e.g. [\"annualTotalRevenue\"
   \"quarterlyDilutedEPS\"]. They are sent in chunks of at most 100 types per
   request (query2, failing over to query1, with the chart endpoint's retry
   and backoff).

   Options:
     :start - epoch seconds (integer) or Instant (default 1985-08-23)
     :end   - epoch seconds (integer) or Instant (default now)

   Returns {:ok? true :data [rows] :request {...}}, rows sorted by :type then
   :period-end:
     {:ticker \"MSFT\" :type \"quarterlyDilutedEPS\" :period-end \"2025-06-30\"
      :period-type \"3M\" :currency \"USD\" :value 3.65}
   Null entries and entries without a value are skipped; types Yahoo has no
   data for produce no rows (so does an unknown ticker). If every chunk is
   made only of types Yahoo does not know, the call fails with :api-error and
   :reason :no-known-types.

   If any chunk fails, the whole call fails: {:ok? false :error {...}} with the
   failing chunk's error (:rate-limited, :http-error, :api-error,
   :parse-error, :connection-error) and no partial data. Bad arguments give
   :invalid-opts before any request."
  [ticker types & {:keys [start end] :as opts}]
  (or (validate-common ticker opts #{:start :end})
      (when-not (and (coll? types) (not (map? types)) (seq types)
                     (every? #(and (string? %) (not (str/blank? %))) types))
        (invalid :types types "types must be a non-empty collection of non-blank strings"))
      (try
        (let [types (vec (distinct types))
              period1 (if start (parse/->epoch start) default-period1)
              period2 (parse/->epoch (or end (Instant/now)))
              chunks (partition-all max-types-per-request types)
              request {:ticker ticker
                       :types types
                       :query-params {:period1 period1 :period2 period2}
                       :chunks (count chunks)}]
          (loop [[chunk & more] chunks
                 rows []
                 unknown-chunks 0]
            (if-not chunk
              (if (= unknown-chunks (count chunks))
                (assoc (parse/err {:type :api-error
                                   :reason :no-known-types
                                   :ticker ticker
                                   :message (str "Yahoo knows none of the requested types ("
                                                 no-known-types-message ")")})
                       :request request)
                (assoc (parse/ok (vec (sort-by (juxt :type :period-end) rows)))
                       :request request))
              (let [result (fetch-chunk ticker chunk period1 period2)]
                (if (:ok? result)
                  (recur more
                         (into rows (:data result))
                         (cond-> unknown-chunks (:no-known-types? result) inc))
                  (assoc result :request request))))))
        (catch Exception e
          (parse/err {:type :exception :ticker ticker :message (.getMessage e)})))))

(defn fetch-timeseries
  "Fetch raw fundamentals-timeseries rows by Yahoo type string (simple API).
   Returns the rows of fetch-timeseries*, or [] on failure."
  [ticker types & {:keys [start end]}]
  (let [result (fetch-timeseries* ticker types :start start :end end)]
    (if (:ok? result) (:data result) [])))

(defn- statement-types
  "[yahoo-type row-info] pairs for the requested fields and frequencies.
   Balance-sheet fields get no trailing type: Yahoo has none."
  [fields frequencies]
  (for [field fields
        frequency frequency-order
        :when (contains? frequencies frequency)
        :let [statement (field-statement field)]
        :when (not (and (= :trailing frequency) (= :balance-sheet statement)))]
    [(str (name frequency) field)
     {:statement statement
      :field (field->keyword field)
      :yahoo-field field
      :frequency frequency}]))

(defn- statement-row-key [{:keys [statement field frequency period-end]}]
  [(.indexOf ^java.util.List statement-order statement)
   (name field)
   (.indexOf ^java.util.List frequency-order frequency)
   period-end])

(defn statements-rows
  "Turn fetch-timeseries* rows into statement rows using `type-info`
   ({yahoo-type {:statement :field :yahoo-field :frequency}}), sorted by
   statement (income statement, balance sheet, cash flow), field name,
   frequency (annual, quarterly, trailing) and period end. Pure function for
   testing."
  [ticker type-info rows]
  (->> rows
       (keep (fn [{:keys [type period-end period-type currency value]}]
               (when-let [info (get type-info type)]
                 (merge {:ticker ticker}
                        info
                        {:period-end period-end
                         :period-type period-type
                         :currency currency
                         :value value}))))
       (sort-by statement-row-key)
       vec))

(defn fetch-statements*
  "Fetch financial statement line items for a ticker (verbose API).

   Options:
     :frequencies - collection of :annual :quarterly :trailing (default all)
     :statements  - collection of :income-statement :balance-sheet :cash-flow
                    (default all)
     :fields      - Yahoo field names, e.g. [\"TotalRevenue\" \"DilutedEPS\"];
                    overrides :statements. Must be in the catalog
                    (statement-fields).
     :start/:end  - epoch seconds (integer) or Instant, as for fetch-timeseries*

   Returns {:ok? true :data [rows] :request {...}} or {:ok? false :error {...}}.
   Each row:
     {:ticker \"AAPL\" :statement :income-statement :field :total-revenue
      :yahoo-field \"TotalRevenue\" :frequency :quarterly
      :period-end \"2025-06-30\" :period-type \"3M\" :currency \"USD\"
      :value 9.4036E10}
   sorted by statement, field, frequency and period end. Only data Yahoo
   returns becomes a row; nothing is filled in. Balance-sheet fields have no
   trailing frequency. Unknown option keys or values give :invalid-opts.

   All statements at all frequencies are about 1000 Yahoo types, i.e. 10
   requests; narrow :statements, :frequencies or :fields to send fewer. If any
   request fails, the whole call fails (see fetch-timeseries*)."
  [ticker & {:keys [frequencies statements fields start end] :as opts}]
  (or (validate-common ticker opts #{:frequencies :statements :fields :start :end})
      (keyword-subset-error :frequencies frequencies (set frequency-order))
      (keyword-subset-error :statements statements (set statement-order))
      (when (some? fields)
        (cond
          (not (and (sequential? fields) (seq fields) (every? string? fields)))
          (invalid :fields fields "fields must be a non-empty vector of Yahoo field names")

          (not-every? field-statement fields)
          (invalid :fields (vec (remove field-statement fields))
                   (str "Unknown field(s): " (str/join ", " (remove field-statement fields))
                        ". See statement-fields for the catalog."))))
      (let [frequencies (set (or frequencies frequency-order))
            statements (set (or statements statement-order))
            fields (or (some-> fields distinct)
                       (mapcat statement-fields (filter statements statement-order)))
            pairs (statement-types fields frequencies)
            type-info (into {} pairs)
            request {:ticker ticker
                     :frequencies frequencies
                     :statements (if (seq (:fields opts)) (set (map field-statement fields)) statements)
                     :fields (vec fields)}]
        (if (empty? pairs)
          (assoc (parse/ok []) :request request)
          (let [result (fetch-timeseries* ticker (map first pairs) :start start :end end)]
            (cond
              (:ok? result)
              (assoc result
                     :data (statements-rows ticker type-info (:data result))
                     :request (merge request (select-keys (:request result) [:query-params :chunks])))

              (= :no-known-types (-> result :error :reason))
              (assoc (parse/ok []) :request request)

              :else
              (assoc result :request request)))))))

(defn fetch-statements
  "Fetch financial statement rows for a ticker (simple API). Options as for
   fetch-statements*. Returns the rows, or [] on failure."
  [ticker & {:keys [frequencies statements fields start end]}]
  (let [result (fetch-statements* ticker
                                  :frequencies frequencies :statements statements
                                  :fields fields :start start :end end)]
    (if (:ok? result) (:data result) [])))

(comment
  (fetch-statements "AAPL" :frequencies [:quarterly] :fields ["TotalRevenue" "DilutedEPS"])

  (fetch-statements* "NVDA" :statements [:balance-sheet] :frequencies [:annual])

  (fetch-timeseries* "MSFT" ["quarterlyDilutedEPS" "trailingTotalRevenue"]))
