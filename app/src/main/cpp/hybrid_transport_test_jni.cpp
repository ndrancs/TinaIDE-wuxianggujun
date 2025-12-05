/**
 * 混合传输测试 JNI - 测试控制通道 + 共享内存集成
 */

#include <jni.h>
#include <android/log.h>
#include <thread>
#include <chrono>
#include <string>
#include <unistd.h>
#include "lsp/native_client/transport/control_channel.h"
#include "lsp/native_client/transport/shared_memory_transport.h"

#define LOG_TAG "HybridTransportTest"
#include "utils/logging.h"

using namespace tinaide::lsp;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wuxianggujun_tinaide_core_lsp_HybridTransportTest_runIntegrationTest(
    JNIEnv* env, jclass, jstring jSocketPath) {

    LOGI("========== 开始混合传输集成测试 ==========");

    try {
        if (jSocketPath == nullptr) {
            LOGE("socketPath is null");
            return JNI_FALSE;
        }

        const char* raw_path = env->GetStringUTFChars(jSocketPath, nullptr);
        std::string socket_path = raw_path ? raw_path : "";
        env->ReleaseStringUTFChars(jSocketPath, raw_path);

        if (socket_path.empty()) {
            LOGE("socketPath is empty");
            return JNI_FALSE;
        }

        ChannelConfig config;
        config.socket_path = socket_path;

        // 服务端线程
        std::thread server_thread([config]() {
            auto server_channel = std::make_shared<ControlChannel>(config);

            if (server_channel->createServer() != ChannelError::SUCCESS) {
                LOGE("Server创建失败");
                return;
            }

            if (server_channel->acceptClient() != ChannelError::SUCCESS) {
                LOGE("Accept失败");
                return;
            }

            bool inline_received = false;
            bool shmem_received = false;
            int attempts = 0;

            while (attempts < 4 && (!inline_received || !shmem_received)) {
                Message msg;
                if (server_channel->receive(msg) != ChannelError::SUCCESS) {
                    LOGE("Receive失败");
                    return;
                }

                attempts++;
                LOGI("Server收到消息: type=%d, size=%u", msg.header.type, msg.header.payload_size);

                if (msg.header.type == static_cast<uint16_t>(MessageType::DATA)) {
                    inline_received = true;
                    LOGI("收到控制通道数据: request_id=%llu, size=%u",
                         static_cast<unsigned long long>(msg.header.request_id),
                         msg.header.payload_size);
                } else if (msg.header.type == static_cast<uint16_t>(MessageType::SHARED_MEMORY_FD)) {
                    shmem_received = true;
                    LOGI("收到共享内存FD: %d, request_id=%llu",
                         msg.fd,
                         static_cast<unsigned long long>(msg.header.request_id));
                    if (msg.fd >= 0) {
                        ::close(msg.fd);
                    }
                } else {
                    LOGI("忽略未知类型: %u", msg.header.type);
                }
            }

            LOGI("Server测试完成 inline=%s shmem=%s",
                 inline_received ? "true" : "false",
                 shmem_received ? "true" : "false");
        });

        std::this_thread::sleep_for(std::chrono::milliseconds(100));

        // 客户端
        auto client_channel = std::make_shared<ControlChannel>(config);
        if (client_channel->connect() != ChannelError::SUCCESS) {
            LOGE("Connect失败");
            server_thread.join();
            return false;
        }

        SharedMemoryTransport transport(client_channel);

        // 测试小数据（走控制通道）
        std::vector<char> small_data(2048, 'A');
        if (!transport.send(100, small_data)) {
            LOGE("发送小数据失败");
            server_thread.join();
            return false;
        }
        LOGI("✅ 小数据测试通过（2KB）");

        std::this_thread::sleep_for(std::chrono::milliseconds(200));

        // 测试大数据（走共享内存）
        std::vector<char> large_data(50 * 1024, 'B');  // 50KB
        if (!transport.send(101, large_data)) {
            LOGE("发送大数据失败");
            server_thread.join();
            return false;
        }
        LOGI("✅ 大数据测试通过（50KB）");

        server_thread.join();
        LOGI("========== 混合传输集成测试通过 ==========");
        return true;

    } catch (const std::exception& e) {
        LOGE("异常: %s", e.what());
        return false;
    }
}
