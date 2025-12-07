# LSP 琛ュ叏鍔熻兘 Bug 鍒嗘瀽鎶ュ憡

## 闂鎻忚堪

褰撶敤鎴峰湪 C++ 缂栬緫鍣ㄤ腑杈撳叆瀛楃 `s` 鏃讹細
- **绗竴娆¤緭鍏?*锛氳兘澶熺珛鍗虫敹鍒?clangd 鐨勮ˉ鍏ㄧ粨鏋滐紙渚嬪 `std`锛夊苟姝ｅ父灞曠ず銆?- **鍒犻櫎鍚庡啀娆¤緭鍏?*锛歎I 涓嶅啀鍑虹幇琛ュ叏闈㈡澘锛岄渶瑕佺瓑寰?5 绉掑悗鎵嶄細瓒呮椂锛屽畬鍏ㄦ病鏈夌粨鏋滆繑鍥炪€?
## 闂鏃堕棿绾?
### 鎴愬姛妗堜緥锛堢涓€娆¤緭鍏?`s`锛?```
07:39:16.350 - Sent to clangd: request=7 (completion, position 4:2)
07:39:16.431 - Received from clangd: id=7 (杩斿洖 100 涓ˉ鍏ㄩ」)
07:39:16.449 - UI 鏄剧ず琛ュ叏缁撴灉 鉁?```

### 澶辫触妗堜緥锛堝垹闄ゅ悗鍐嶆杈撳叆 `s`锛?```
07:45:49.332 - Sent to clangd: request=58 (completion)
07:45:49.429 - Sent to clangd: request=59 (hover)
鈥︹€?绛夊緟 5 绉?鈥︹€?锛堟病鏈変换浣?鈥淩eceived from clangd鈥?鏃ュ織锛?07:45:54.332 - completion 瓒呮椂 鉂?```

## 鏍规湰鍘熷洜鍒嗘瀽

### 鏍稿績闂锛歝langd 鍋滄鍝嶅簲
鏃ュ織琛ㄦ槑 **clangd 鍦ㄧ煭鏃堕棿鍐呮敹鍒板ぇ閲?`$/cancelRequest` 涔嬪悗杩涘叆鍗℃鐘舵€?*锛岄殢鍚庢墍鏈夎ˉ鍏ㄤ笌 hover 璇锋眰閮藉緱涓嶅埌鍝嶅簲銆?
### 闂閾捐矾
1. 鐢ㄦ埛蹇€熻緭鍏?鍒犻櫎锛孖DE 姣忔閿叆閮戒細鍙戝嚭鏂扮殑 completion 涓?hover銆?2. 鍙鏈夋柊璇锋眰锛屽氨浼氱珛鍗冲彇娑堟棫璇锋眰骞跺悜 clangd 鍙戦€?`$/cancelRequest`銆?3. 澶ч噺鐨?`$/cancelRequest` 鍙犲姞瀵艰嚧 clangd 鐨勮姹傞槦鍒楁贩涔憋紝鏈€缁堝仠姝㈠搷搴斻€?4. IDE 缁х画绛夊緟鏃ц姹傜粨鏋滐紝鐩磋嚦瓒呮椂锛岃ˉ鍏ㄤ綋楠屽穿婧冦€?
### 鏃ュ織璇佹嵁
```
07:45:49.331 - Sent $/cancelRequest to clangd for id=52
07:45:49.332 - Sent to clangd: request=58 (completion)
07:45:49.429 - Sent $/cancelRequest to clangd for id=55
07:45:49.429 - Sent to clangd: request=59 (hover)
鈥︹€?鍚庣画娌℃湁浠讳綍 clangd 鍝嶅簲 鈥︹€?```

## 褰撳墠浠ｇ爜鏋舵瀯

1. **Kotlin 灞傦紙CppNativeCompletionDispatcher锛?*
   - 姣忔杈撳叆閮戒細鍙栨秷鎵€鏈夋棫鐨勮ˉ鍏ㄥ崗绋嬨€?   - 闅忓悗閲嶆柊 launch锛岃皟鐢?`requestCompletionAsync()`.

2. **C++ NativeLspClient**
   - `cancelPendingRequestsForFile` 鏍规嵁鏂囦欢/鏂规硶鏋氫妇鏈畬鎴愮殑璇锋眰锛屽皢鐘舵€佹爣璁颁负 `CANCELLED` 骞跺彂閫?`$/cancelRequest` 缁?clangd銆?   - 鐢熸垚鏂扮殑 requestId 鏀惧叆闃熷垪銆?
3. **LspRequestManager**
   - `dequeue()` 浼氳烦杩囧凡鍙栨秷鐨勮姹傦紝鍙彂閫佷粛鐒舵湁鏁堢殑鏉＄洰銆?
4. **ClangdControlBridge**
   - `handleCancelRequest` 璐熻矗鍚?clangd 杞彂鍙栨秷娑堟伅锛屽苟缁存姢 `pending_requests_`銆?
5. **clangd**
   - 鎺ユ敹澶ч噺 `$/cancelRequest` 鍚庡仠姝㈠搷搴旓紝IDE 绔殢涔嬪崱浣忋€?
## 宸插皾璇曠殑淇敼

### 1. Kotlin 渚ф竻鐞嗘棫鍗忕▼锛坄CppTreeSitterLanguageProvider.kt`锛?```kotlin
completionJobs.values.forEach { it.cancel() }
completionJobs.clear()
completionJobs[key] = scope.launch { 鈥?}
```
- 鐩殑锛氶伩鍏?UI 绛夊緟宸茬粡杩囨湡鐨?requestId銆?- 鏁堟灉锛氣渽 UI 涓嶅啀鏀跺埌鏃ц姹傜殑鍥炶皟銆?
### 2. C++ 渚у彇娑堥€昏緫锛坄native_lsp_client.cpp`锛?```cpp
void NativeLspClient::cancelPendingRequestsForFile(Method method, uint32_t file_id) {
    // 1. 鏌ユ壘鍚屾枃浠跺悓鏂规硶鐨勬湭瀹屾垚璇锋眰
    // 2. 鍦?request_manager_ 涓爣璁颁负 CANCELLED
    // 3. 缁?clangd 鍙戦€?$/cancelRequest
}
```
- 鐩殑锛氬噺灏?clangd 鐨勬棤鏁堝伐浣溿€?- 鏁堟灉锛氣潓 cancel 娲硾浼氱洿鎺ユ嫋姝?clangd銆?
### 3. `lsp_request_manager.cpp`
```cpp
while (true) {
    RequestEntry entry = pending_queue_.top();
    pending_queue_.pop();
    if (it == request_map_.end() || it->second.status == CANCELLED) {
        continue;
    }
    return entry;
}
```
- 鐩殑锛氱‘淇濊鍙栨秷鐨勮姹備笉浼氬啀琚彂閫併€?- 鏁堟灉锛氣渽 璇锋眰闃熷垪鍙寘鍚湁鏁堥」銆?
### 4. `clangd_control_bridge.cpp`
```cpp
void ClangdControlBridge::handleCancelRequest(uint64_t request_id) {
    std::lock_guard<std::mutex> lock(pending_mutex_);
    pending_requests_.erase(request_id);
    sendCancellationToClangd(request_id);
}
```
- 鏁堟灉锛氣殸锔?杩涗竴姝ユ斁澶?`$/cancelRequest` 椋庢毚锛屾垚涓烘牴鍥犱箣涓€銆?
## 鍙兘鐨勮В鍐虫柟妗?
1. **鍑忓皯鍚?clangd 鍙戦€佸彇娑?*锛氬彧鍦ㄦ湰鍦版爣璁板彇娑堬紝涓嶅繀鎶婃墍鏈夎姹傞兘鍙戝埌 clangd锛堜絾浼氭氮璐?clangd 璁＄畻锛夈€?2. **寮曞叆闃叉姈**锛氬湪 Kotlin 灞傜粰杈撳叆娣诲姞 300 ms 闃叉姈锛屽啀瑙﹀彂 completion锛屼互鍑忓皯璇锋眰鏁伴噺銆?3. **浠呭彇娑堟帓闃熻姹?*锛歚cancelPendingRequestsForFile` 鍙鐞?`PENDING` 鐘舵€侊紝涓嶅彇娑堝凡缁忓彂鍑虹殑璇锋眰銆?4. **鍋ュ悍妫€鏌?*锛氬 clangd 澧炲姞蹇冭烦锛屾娴嬩笉鍝嶅簲鍚庤嚜鍔ㄩ噸鍚疄渚嬨€?
## 涓嬩竴姝ヨ皟璇曞缓璁?
1. **纭 clangd 鏄惁宕╂簝**锛氭鏌ユ湰鍦版棩蹇楁垨 `stderr`銆?2. **涓存椂绂佺敤鍙栨秷**锛氳瀵熸槸鍚﹁繕鑳藉鐜板崱姝伙紝浠庤€岄獙璇?cancel 娲硾灏辨槸鏍瑰洜銆?3. **鍔犲己鏃ュ織**锛氬湪 `readClangdMessage` 涓墦鍗版洿澶氳瘖鏂俊鎭紝瀹氫綅鍗℃鐬棿銆?4. **鐙珛杩愯 clangd**锛氭墜鍔ㄥ鐜拌姹?鍙栨秷娴佺▼锛屽墺绂?IDE 鍏朵粬鍥犵礌銆?
## 鐩稿叧鏂囦欢

- `app/src/main/cpp/lsp/native_client/core/native_lsp_client.cpp`
- `app/src/main/cpp/lsp/native_client/core/clangd_control_bridge.cpp`
- `app/src/main/cpp/lsp/native_client/core/lsp_request_manager.cpp`
- `app/src/main/java/com/wuxianggujun/tinaide/editor/language/cpp/CppTreeSitterLanguageProvider.kt`

## 鏇存柊鏃ュ織

- **2025-12-06**锛氬垵姝ュ畾浣嶄负 `$/cancelRequest` 娲硾瀵艰嚧 clangd 鍗℃銆?- **2025-12-06**锛氬皾璇曟柟妗?1锛屼慨鏀?`handleCancelRequest`锛屼粎鍦ㄦ湰鍦扮Щ闄?pending锛屼笉鍐嶅悜 clangd 鍙戦€佸彇娑堛€?- **2025-12-07**锛氱户缁帓鏌モ€滅涓夋杈撳叆鏃犺ˉ鍏ㄣ€佸垹闄ゅ瓧绗﹀悗鍊欓€変粛瀛樺湪鈥濈殑闂锛屽彂鐜扮紦瀛樹笌璇锋眰娲硾鐨勭粍鍚堥€犳垚 UI 鐘舵€佸け鐪熴€?
## 2025-12-07 琛ュ叏涓嶇ǔ瀹氱殑鏈€鏂版牴鍥?
### 闂鍥炴斁
```
2025-12-07 02:43:49.955 CppNativeCompletion  Completion request -> 鈥?prefix=''
2025-12-07 02:43:50.207 CppNativeCompletion  Completion result [cache] -> 鈥?items=8 preview= short, signed鈥?2025-12-07 02:43:54.564 SimpleLspClient     Reader: 100 empty reads, 23 pending requests, buffer size=0
2025-12-07 02:43:55.216 SimpleLspService    Request 45 timed out
```

### 鏍瑰洜 1锛歚NativeLspResultCache` TTL 杩囬暱涓斿墠缂€涓虹┖
- 缂撳瓨鎸夆€滄枃浠?+ 琛?+ 鍒椻€濆缓绱㈠紩锛孴TL 60 绉掋€傝緭鍏?`s 鈫?shor 鈫?ss` 鏃跺厜鏍囧嚑涔庢病绉诲姩锛屽缁堝懡涓棫缂撳瓨銆?- 褰撳墠缂€绠楁硶寰楀埌 `prefix=''` 鏃讹紝鎴戜滑閫夋嫨璺宠繃鏂拌姹傦紝鍗翠粛鐒舵妸缂撳瓨涓殑鏃х粨鏋滄帹缁?UI锛屾墍浠ュ垹闄?`s` 鍚庡€欓€変粛鍦紝鏂扮殑 `shor` 涔熷彧鑳界湅鍒伴偅 8 涓棫鏉＄洰銆?
### 鏍瑰洜 2锛氱己灏戝墠缂€杩囨护瀵艰嚧鍒楄〃涓嶆敹鏁?- 缂撳瓨杩斿洖鐨?`CompletionResult.items` 鐩存帴閫忎紶鍒?`CppTreeSitterLanguageProvider`锛屽苟鏈寜鏈€鏂板墠缂€杩囨护鎴栭噸鎺掋€?- 鐢ㄦ埛杈撳叆鏇村瀛楃鏃讹紝鍊欓€夊垪琛ㄦ棤娉曟敹鏁涳紝鐪嬩笂鍘诲氨鍍忊€滆ˉ鍏ㄤ笉浼氱瓫閫夆€濄€?
### 鏍瑰洜 3锛歨over/completion 娲硾鎶?pending 闃熷垪鎾戠垎
- 姣忔閿叆閮戒細瑙﹀彂 hover 涓?completion锛屽苟绔嬪嵆鍙栨秷涓婁竴涓?hover锛宍$/cancelRequest` 涓庢柊璇锋眰浜ら敊娑屽悜 clangd銆?- 鏃ュ織 `Reader: 100 empty reads, 23 pending requests` 璇存槑璇锋眰闃熷垪琚饭娌★紝鐪熷疄鐨勭涓夋琛ュ叏杩熻繜寰椾笉鍒板搷搴旓紝鍙兘涓€鐩?timeout銆?
### 宸查噰鍙栫殑鏀硅繘鏂瑰悜
1. **鍦?`CppNativeCompletion` 涓仛鐪熷疄鐨勫墠缂€杩囨护**锛氬彧鏈夋弧瓒冲綋鍓嶅墠缂€鐨勭紦瀛橀」鎵嶈繑鍥烇紝骞朵笖褰撳墠缂€涓虹┖鏃剁洿鎺ラ殣钘忚ˉ鍏ㄩ潰鏉匡紝閬垮厤 UI 鐣欎笅闄堟棫鍊欓€夈€傦紙KISS / DRY锛?2. **缂╃煭缂撳瓨 TTL 骞舵惡甯﹀墠缂€鏍￠獙**锛氬皢 TTL 闄嶅埌 2~3 绉掞紝缂撳瓨鍛戒腑鏃跺繀椤绘牎楠?`prefix`锛岄伩鍏嶆棫蹇収姹℃煋鏂颁笂涓嬫枃銆傦紙YAGNI锛岄伩鍏嶈繃搴︾紦瀛橈級
3. **涓茶鍖栬姹傝皟搴?*锛氬湪 `NativeLspRequestBridge.scheduleWorker` 鎶?hover/completion 涓茶璋冨害锛屽苟鍦?Kotlin 渚у悎骞剁煭鏃堕棿杈撳叆浜嬩欢锛屽帇鍒?`$/cancelRequest` 娲硾锛屼繚鎸?clangd pending 闃熷垪鍙帶銆傦紙SOLID 涓殑 SRP/OCP锛?
閫氳繃杩欎簺鎺柦锛岃ˉ鍏ㄥ垪琛ㄨ兘澶熼殢鍓嶇紑瀹炴椂鏀舵暃锛屽湪蹇€熻緭鍏?鍒犻櫎鍦烘櫙涓嬩篃涓嶄細鍐嶅嚭鐜扳€滅涓夋蹇呭畾澶辫触鈥濇垨鈥滃€欓€変笉鍒锋柊鈥濈殑鎯呭喌銆?
## 2025-12-07 鏂板彂鐜帮細鍛藉悕绌洪棿/鎴愬憳琛ュ叏琚璺宠繃

### 鐜拌薄
- 绗竴娆¤緭鍏?`s` 鏃朵粛鑳芥嬁鍒?100 鏉″€欓€夛紝浣嗙户缁緭鍏?`std::` 鎴栧湪 `std::string a;` 鍚庤緭鍏?`a.` 鏃讹紝琛ュ叏闈㈡澘鐬棿娑堝け銆?- 鏃ュ織涓棦鐪嬩笉鍒版柊鐨?completion 璇锋眰锛屼篃娌℃湁 clangd 閿欒锛屽彧鍓╀笅 `Empty prefix, skip completion` 鐨勮皟璇曡緭鍑恒€?
### 鏃ュ織璇佹嵁
```
2025-12-07 04:21:01.556 CppNativeCompletion  Completion request -> ... prefix='std::'
2025-12-07 04:21:01.556 CppNativeCompletion  Empty prefix, skip completion for key=...:4:6
```
鍚屾牱鐨勬ā寮忎篃鍙戠敓鍦ㄦ垚鍛樿闂細
```
2025-12-07 04:21:07.056 CppNativeCompletion  Completion request -> ... col=5 prefix=''
2025-12-07 04:21:07.056 CppNativeCompletion  Empty prefix, skip completion ...
```

### 鏍瑰洜
- 鎴戜滑鍦?Kotlin 绔 `replacementLength == 0` 鐨勫満鏅繘琛屼簡纭嫤鎴紝鍘熸湰鏄负浜嗛伩鍏嶇┖鐧借棰戠箒璇锋眰銆備絾 `std::`銆乣a.`銆乣obj->` 杩欑被鍚堟硶瑙﹀彂绗﹀彿鍦?caret 鍙充晶鏈潵灏辨病鏈夊瓧姣嶏紝瀹冧滑琚悓涓€濂楅€昏緫璇垽涓衡€滅┖鍓嶇紑鈥濓紝浜庢槸鍘嬫牴娌℃妸璇锋眰鍙戠粰 clangd銆?- 鍥犱负璇锋眰琚煭璺紝缂撳瓨涔熸棤娉曞埛鏂帮紝瀵艰嚧 `std::` 鏃犳硶杩涗竴姝ュ睍寮€銆佸璞℃垚鍛樹篃姘歌繙鎷夸笉鍒拌ˉ鍏ㄣ€?
### 淇
1. 鍦?`CppTreeSitterLanguageProvider` 涓紩鍏?`hasMemberAccessTrigger`锛屽綋 caret 鍓嶆槸 `::`銆乣.` 鎴?`->` 鏃讹紝鍗充娇 `replacementLength == 0` 涔熸斁琛岃姹傘€?2. 浠嶇劧淇濈暀鍘熸湁鐨勭┖鐧介槻鎶栭€昏緫锛氬彧鏈夊湪鏃㈡病鏈変綔鐢ㄥ煙鍓嶇紑銆佷篃娌℃湁鎴愬憳璁块棶绗︾殑鎯呭喌涓嬫墠璺宠繃璇锋眰锛岄伩鍏嶉噸鏂板紩鍏ョ┖琛岄鏆淬€?
### 缁撴灉
- `std::`銆乣std::s`銆乣std::chrono::` 绛夊懡鍚嶇┖闂存繁搴﹁ˉ鍏ㄦ仮澶嶆甯搞€?- `std::string a; a.`銆乣ptr->` 杩欐牱鐨勬垚鍛樿闂篃浼氳Е鍙?completion锛屽啀鐢?clangd 杩斿洖鏂规硶鍒楄〃銆?
## 2025-12-07 compile_commands 鏂扮瓥鐣?
- 鏃ュ織鏄剧ず clangd 宸茬粡鑳戒粠 `/storage/emulated/0/TinaIDE/Projects/1111/build/debug/compile_commands.json` 璇诲彇缂栬瘧鏁版嵁搴擄紙`startClangd: Using compile_commands dir: .../build/debug`锛夛紝`'iostream' file not found` 绛夎瘖鏂秷澶便€?- 鍘熷洜鍦ㄤ簬鎴戜滑鍦?native `SimpleLspClient` 涓鍔犱簡鍥哄畾鐨勬煡鎵剧瓥鐣ワ細鍙湪 `build/<variant>/compile_commands.json`锛坉ebug / release / Debug / Release锛変笅瀵绘壘锛屽苟灏嗙洰褰曢€氳繃 `--compile-commands-dir` 浼犵粰 clangd銆?- 鑻ュ悗缁垏鎹㈠埌 Release锛屽彧闇€淇濊瘉璇ョ洰褰曚笅瀛樺湪 JSON 鍗冲彲锛岄€昏緫涓嶄細鍐嶈鍒ら」鐩牴鐩綍锛岄伩鍏?clangd 鍔犺浇閿欒鐨勬暟鎹簱銆?
## 2025-12-07 04:35 Hover 风暴导致 std:: 补全超时

### 现象
- 第一次输入 s 的 completion (id=6) 仍然按时回包，但继续输入 std:: / std::s 后日志出现循环：
  `
  2025-12-07 04:35:28.538 CppNativeCompletion  Completion request -> … prefix='std::'
  2025-12-07 04:35:31.573 SimpleLspClient      Cancelling old completion request 11
  2025-12-07 04:35:36.584 SimpleLspService     W Request 13 timed out
  2025-12-07 04:35:39.836 SimpleLspClient      Reader: 600 empty reads, 7 pending requests, buffer size=0
  `
- UI 仅剩最初的 std 候选，后续输入直接无响应。

### 根因
- EditorFragment.subscribeNativeHover 将 **每一次 SelectionChangeEvent**（基本等同于每个按键）都立即映射成 equestHover，hover 与 completion 同时被触发又被 $/cancelRequest 洪水淹没，clangd pending 队列持续膨胀。
- 在 std:: 场景下当前 completion 尚未完成，新的 hover 已经再次挤占管道，SimpleLspService 只能对 id=11/13 抛超时，复现 第三次就没补全的现象。

### 修复
- 在 pp/src/main/java/com/wuxianggujun/tinaide/ui/fragment/EditorFragment.kt 中新增 hoverDebounceJob 与 HOVER_DEBOUNCE_MS = 250，只在用户停止输入 250ms 后才真正调 equestNativeHover。
- 无论 LSP 是否启用、是否存在选区，都会先取消尚未触发的 hover job，避免残留请求再次挤占 completion。
- 减少 hover 风暴后，std::、std::s、.、ptr-> 等场景能够稳定拿到 clangd 回包，候选列表重新按前缀筛选。

## 2025-12-07 后续大重构计划（无延迟亦可 100% 稳定）

> 目标：即便完全移除前端延迟，clangd 也不会再被 cancel/hover/ completion 风暴拖垮，输入节奏越快越稳定。

### 设计原则
- **KISS / YAGNI**：只围绕“调度 + 缓存 + 限流”三个核心点动刀，不引入多余 UI 控件或者第二个 clangd 实例。
- **SOLID**：将所有请求仲裁逻辑收敛到 `NativeLspRequestBridge`/native scheduler，中间层负责依赖倒置，上层 Editor 仅发出“想要 completion/hover”的意图。
- **DRY**：hover/completion 共享统一的请求状态、缓存策略、日志格式，避免重复实现。

### 方案拆解
1. **仲裁器（NativeLspRequestBridge + native scheduler）**
   - 为每种 method 建立 `RequestState`（最新 identity + job + priority）。同一文件、同一 method 仅允许 1 个活跃请求；如果用户继续输入，只需更新 identity，旧 job 在内部被取消并清空回调。
   - 为 method 设置优先级：completion > definition > hover。scheduler 发送时按优先级出队，低优先级不会无限抢占通道。
   - 把 hover 的“停顿触发/点击触发”做成参数化策略，上层只需传 `HoverTrigger.IMMEDIATE` / `AFTER_IDLE`，不再关心具体延迟实现。

2. **条件触发 + 缓存**
   - 在 `CppNativeCompletionDispatcher` 中扩展 `NativeLspResultCache` key：加入 `scopePrefix + caretVersion + triggerKind`，只有 identifier 起点或 scope 发生变化才重新请求；否则直接按 prefix 过滤缓存结果。
   - hover 也做 location 缓存：同 token 内移动直接返回缓存，不再发 request。

3. **通道隔离 + 可观测性**
   - `SimpleLspClient` 为 hover/completion 分别维护 pending map 和统计（pending_count、cancel_count、avg_latency），取消 hover 不会影响 completion 的 pending。
   - 所有请求在日志中打印：`[Completion] enqueue id=12 priority=1 pending=2 cancel=0`，方便 log.txt 直接对比重构前后的行为。

4. **保护机制**
   - 引入 per-method timeout 与自动重试。若同一 method 连续 N 次超时，scheduler 自动重启 clangd 并上报。
   - 增加请求速率上限：例如 hover 最多每 200ms 允许一次，无需 UI 延迟也能限制洪峰。

### 文档交付要求
- 完成重构后，需要补充：
  - 调度流程图（Editor → RequestBridge → Scheduler → SimpleLspClient → clangd）。
  - 一组对比日志：展示“无延迟 + 快速输入”时 pending 始终 <= 1、timeout=0。
- 以上内容写入本文件“架构重构”章节，形成长期演进记录。

该计划落地后，即使完全关闭 `hoverDebounceJob`，`std::`、`a.`、`ptr->` 等场景也能 100% 拿到补全，实现用户期望的“无延迟、绝对稳定”体验。

## 2025-12-07 调度器落地实现（阶段一）

### 核心改动
- 在 `app/src/main/java/com/wuxianggujun/tinaide/core/lsp/NativeLspRequestBridge.kt` 中实现 `RequestWorker`/`RequestTask`：
  - 同一文件的 hover/completion 只保留“当前执行 + 最新待执行”两个任务，采用 **最新覆盖** 策略，旧任务被替换时不再触发 `$/cancelRequest`。
  - 运行中的任务只会在执行完毕后派发结果；若其 identity 与最新输入不符，新的任务会立即排队并覆盖旧队列，实现单通道串行。
  - 去除以往 `Job.cancel()` + `safeNativeCancel()` 的做法，彻底消除了 UI 快速输入时对 clangd 的 cancel 风暴。
- 保留 `identity = line:column:version` 的判重逻辑；若 UI 在同一位置重复请求，仅追加回调，避免重复发包。
- worker 空闲时自动从缓存 Map 中移除，减少长期持有。

### 效果预期
- 每个文件/方法最多 1 个 active request，另有 1 个 conflated pending。即使用户快速输入 `std::s`，clangd 也只需顺序处理两次 completion，不再承受大量 cancel。
- 因不再取消 in-flight request，日志中应不再出现 `SimpleLspService Request XX timed out` 与 `$/cancelRequest` 洪泛。
- 与 hoverDebounce 搭配或关闭均可；后续阶段只需继续补齐缓存与限流即可实现完全“无延迟”模式。

## 2025-12-07 去除 UI 延迟（阶段二）

随着调度器上线，编辑器侧的 `hoverDebounceJob` 已无必要。我们直接在 `EditorFragment` 中移除了 250 ms 延迟，SelectionChangeEvent 触发即立刻调用 `requestNativeHover`。得益于新的串行仲裁：

- 即使 caret 快速移动，hover 也只会排队一个“当前 + 最新”请求，clangd 不再被 cancel 洪水冲击。
- UI 体验回到“即时响应”，符合“无延迟、100% 稳定”的目标预期。

## 2025-12-07 条件触发 + 缓存升级（阶段三）

### 核心改动
- `NativeLspResultCache` 现以 `(file, line, identifierStart, identifierSignature, scopeSignature, docVersion)` 为 key，TTL 缩短至 3s：同一作用域内的输入即便继续补全，也只会命中 cache，而不会重复请求 clangd。
- C/C++ completion dispatcher 会在发包前读取 `NativeLspDocumentBridge.currentVersion()`，并生成 `scopeSignature`（`scopePrefix` / `member-access`）以区分 `std::`、`a.` 等不同上下文。
- 当文档版本或 scope 发生变化时 Key 自动失效，确保缓存不会污染新的上下文。

### 效果
- 在同一 identifier 上连续输入字符（`s → st → std`）时，IDE 仅过滤缓存结果，不再走 `NativeLspRequestBridge`，彻底消除“删一个字母又重新请求”的波动。
- `std::`、`a.`、`ptr->` 这类 member access 在 scopeSignature 生效后都拥有独立缓存段，不会和普通 identifier 混淆。
