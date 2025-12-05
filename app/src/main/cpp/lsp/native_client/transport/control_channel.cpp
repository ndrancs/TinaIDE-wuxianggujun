#include "control_channel.h"
#include <android/log.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/select.h>
#include <errno.h>
#include <cstring>

#define LOG_TAG "ControlChannel"
#include "utils/logging.h"

namespace tinaide {
namespace lsp {

const char* channelErrorToString(ChannelError error) {
    switch (error) {
        case ChannelError::SUCCESS: return "Success";
        case ChannelError::CONNECTION_FAILED: return "Connection failed";
        case ChannelError::SEND_FAILED: return "Send failed";
        case ChannelError::RECEIVE_FAILED: return "Receive failed";
        case ChannelError::TIMEOUT: return "Timeout";
        case ChannelError::PEER_CLOSED: return "Peer closed";
        case ChannelError::INVALID_MESSAGE: return "Invalid message";
        case ChannelError::BUFFER_OVERFLOW: return "Buffer overflow";
        case ChannelError::FD_LIMIT_EXCEEDED: return "FD limit exceeded";
        default: return "Unknown error";
    }
}

ControlChannel::ControlChannel(const ChannelConfig& config)
    : config_(config), socket_fd_(-1), listen_fd_(-1), is_server_(false) {
}

ControlChannel::~ControlChannel() {
    close();
}

ControlChannel::ControlChannel(ControlChannel&& other) noexcept
    : config_(std::move(other.config_)),
      socket_fd_(other.socket_fd_),
      listen_fd_(other.listen_fd_),
      is_server_(other.is_server_),
      last_error_(std::move(other.last_error_)) {
    other.socket_fd_ = -1;
    other.listen_fd_ = -1;
}

ControlChannel& ControlChannel::operator=(ControlChannel&& other) noexcept {
    if (this != &other) {
        close();
        config_ = std::move(other.config_);
        socket_fd_ = other.socket_fd_;
        listen_fd_ = other.listen_fd_;
        is_server_ = other.is_server_;
        last_error_ = std::move(other.last_error_);
        other.socket_fd_ = -1;
        other.listen_fd_ = -1;
    }
    return *this;
}

ChannelError ControlChannel::createServer() {
    if (listen_fd_ >= 0) {
        last_error_ = "Server already created";
        return ChannelError::CONNECTION_FAILED;
    }
    listen_fd_ = socket(AF_UNIX, SOCK_STREAM, 0);
    if (listen_fd_ < 0) {
        last_error_ = std::string("socket() failed: ") + strerror(errno);
        LOGE("%s", last_error_.c_str());
        return ChannelError::CONNECTION_FAILED;
    }
    unlink(config_.socket_path.c_str());
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, config_.socket_path.c_str(), sizeof(addr.sun_path) - 1);
    if (bind(listen_fd_, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        last_error_ = std::string("bind() failed: ") + strerror(errno);
        LOGE("%s", last_error_.c_str());
        ::close(listen_fd_);
        listen_fd_ = -1;
        return ChannelError::CONNECTION_FAILED;
    }
    if (listen(listen_fd_, 5) < 0) {
        last_error_ = std::string("listen() failed: ") + strerror(errno);
        LOGE("%s", last_error_.c_str());
        ::close(listen_fd_);
        listen_fd_ = -1;
        return ChannelError::CONNECTION_FAILED;
    }
    is_server_ = true;
    LOGI("Server created on %s", config_.socket_path.c_str());
    return ChannelError::SUCCESS;
}

ChannelError ControlChannel::acceptClient(uint32_t timeout_ms) {
    if (listen_fd_ < 0) {
        last_error_ = "Server not created";
        return ChannelError::CONNECTION_FAILED;
    }
    if (socket_fd_ >= 0) {
        last_error_ = "Already connected";
        return ChannelError::CONNECTION_FAILED;
    }
    if (timeout_ms > 0) {
        fd_set read_fds;
        FD_ZERO(&read_fds);
        FD_SET(listen_fd_, &read_fds);
        struct timeval tv;
        tv.tv_sec = timeout_ms / 1000;
        tv.tv_usec = (timeout_ms % 1000) * 1000;
        int ret = select(listen_fd_ + 1, &read_fds, nullptr, nullptr, &tv);
        if (ret == 0) {
            last_error_ = "Accept timeout";
            return ChannelError::TIMEOUT;
        } else if (ret < 0) {
            last_error_ = std::string("select() failed: ") + strerror(errno);
            LOGE("%s", last_error_.c_str());
            return ChannelError::CONNECTION_FAILED;
        }
    }
    socket_fd_ = accept(listen_fd_, nullptr, nullptr);
    if (socket_fd_ < 0) {
        last_error_ = std::string("accept() failed: ") + strerror(errno);
        LOGE("%s", last_error_.c_str());
        return ChannelError::CONNECTION_FAILED;
    }
    LOGI("Client connected (fd=%d)", socket_fd_);
    return ChannelError::SUCCESS;
}

ChannelError ControlChannel::connect(uint32_t timeout_ms) {
    if (socket_fd_ >= 0) {
        last_error_ = "Already connected";
        return ChannelError::CONNECTION_FAILED;
    }
    socket_fd_ = socket(AF_UNIX, SOCK_STREAM, 0);
    if (socket_fd_ < 0) {
        last_error_ = std::string("socket() failed: ") + strerror(errno);
        LOGE("%s", last_error_.c_str());
        return ChannelError::CONNECTION_FAILED;
    }
    int flags = fcntl(socket_fd_, F_GETFL, 0);
    fcntl(socket_fd_, F_SETFL, flags | O_NONBLOCK);
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, config_.socket_path.c_str(), sizeof(addr.sun_path) - 1);
    int ret = ::connect(socket_fd_, (struct sockaddr*)&addr, sizeof(addr));
    if (ret < 0 && errno != EINPROGRESS) {
        last_error_ = std::string("connect() failed: ") + strerror(errno);
        LOGE("%s", last_error_.c_str());
        ::close(socket_fd_);
        socket_fd_ = -1;
        return ChannelError::CONNECTION_FAILED;
    }
    if (ret < 0) {
        fd_set write_fds;
        FD_ZERO(&write_fds);
        FD_SET(socket_fd_, &write_fds);
        struct timeval tv;
        tv.tv_sec = timeout_ms / 1000;
        tv.tv_usec = (timeout_ms % 1000) * 1000;
        ret = select(socket_fd_ + 1, nullptr, &write_fds, nullptr, &tv);
        if (ret == 0) {
            last_error_ = "Connect timeout";
            ::close(socket_fd_);
            socket_fd_ = -1;
            return ChannelError::TIMEOUT;
        } else if (ret < 0) {
            last_error_ = std::string("select() failed: ") + strerror(errno);
            LOGE("%s", last_error_.c_str());
            ::close(socket_fd_);
            socket_fd_ = -1;
            return ChannelError::CONNECTION_FAILED;
        }
        int error = 0;
        socklen_t len = sizeof(error);
        getsockopt(socket_fd_, SOL_SOCKET, SO_ERROR, &error, &len);
        if (error != 0) {
            last_error_ = std::string("Connection failed: ") + strerror(error);
            LOGE("%s", last_error_.c_str());
            ::close(socket_fd_);
            socket_fd_ = -1;
            return ChannelError::CONNECTION_FAILED;
        }
    }
    fcntl(socket_fd_, F_SETFL, flags);
    LOGI("Connected to %s (fd=%d)", config_.socket_path.c_str(), socket_fd_);
    return ChannelError::SUCCESS;
}

ChannelError ControlChannel::send(const Message& msg) {
    if (socket_fd_ < 0) {
        last_error_ = "Not connected";
        return ChannelError::SEND_FAILED;
    }
    if (msg.payload.size() > config_.max_message_size) {
        last_error_ = "Message too large";
        return ChannelError::BUFFER_OVERFLOW;
    }
    std::lock_guard<std::mutex> lock(send_mutex_);
    ChannelError err = sendBytes(&msg.header, sizeof(MessageHeader));
    if (err != ChannelError::SUCCESS) return err;
    if (msg.header.payload_size > 0) {
        err = sendBytes(msg.payload.data(), msg.payload.size());
        if (err != ChannelError::SUCCESS) return err;
    }
    if (msg.fd >= 0) {
        err = sendFd(msg.fd);
        if (err != ChannelError::SUCCESS) return err;
    }
    return ChannelError::SUCCESS;
}

ChannelError ControlChannel::receive(Message& msg, uint32_t timeout_ms) {
    if (socket_fd_ < 0) {
        last_error_ = "Not connected";
        return ChannelError::RECEIVE_FAILED;
    }
    std::lock_guard<std::mutex> lock(recv_mutex_);
    uint32_t actual_timeout = timeout_ms > 0 ? timeout_ms : config_.recv_timeout_ms;
    if (actual_timeout > 0) {
        setSocketTimeout(socket_fd_, actual_timeout, false);
    }
    ChannelError err = receiveBytes(&msg.header, sizeof(MessageHeader));
    if (err != ChannelError::SUCCESS) return err;
    if (msg.header.payload_size > config_.max_message_size) {
        last_error_ = "Received message too large";
        return ChannelError::BUFFER_OVERFLOW;
    }
    if (msg.header.payload_size > 0) {
        msg.payload.resize(msg.header.payload_size);
        err = receiveBytes(msg.payload.data(), msg.header.payload_size);
        if (err != ChannelError::SUCCESS) return err;
    } else {
        msg.payload.clear();
    }
    if (msg.header.type == static_cast<uint16_t>(MessageType::SHARED_MEMORY_FD)) {
        err = receiveFd(msg.fd);
        if (err != ChannelError::SUCCESS) return err;
    } else {
        msg.fd = -1;
    }
    return ChannelError::SUCCESS;
}

ChannelError ControlChannel::sendData(uint64_t request_id, const std::vector<uint8_t>& data) {
    Message msg(MessageType::DATA, request_id, data);
    return send(msg);
}

ChannelError ControlChannel::sendSharedMemoryFd(uint64_t request_id, int fd, uint32_t size) {
    std::vector<uint8_t> payload(sizeof(uint32_t));
    memcpy(payload.data(), &size, sizeof(uint32_t));
    Message msg(MessageType::SHARED_MEMORY_FD, request_id, payload, fd);
    return send(msg);
}

void ControlChannel::close() {
    if (socket_fd_ >= 0) {
        ::close(socket_fd_);
        socket_fd_ = -1;
        LOGI("Connection closed");
    }
    if (listen_fd_ >= 0) {
        ::close(listen_fd_);
        listen_fd_ = -1;
        unlink(config_.socket_path.c_str());
        LOGI("Server closed");
    }
}

ChannelError ControlChannel::sendBytes(const void* data, size_t size) {
    const uint8_t* ptr = static_cast<const uint8_t*>(data);
    size_t sent = 0;
    while (sent < size) {
        ssize_t n = ::send(socket_fd_, ptr + sent, size - sent, MSG_NOSIGNAL);
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                last_error_ = "Send timeout";
                return ChannelError::TIMEOUT;
            } else if (errno == EPIPE || errno == ECONNRESET) {
                last_error_ = "Peer closed";
                return ChannelError::PEER_CLOSED;
            } else {
                last_error_ = std::string("send() failed: ") + strerror(errno);
                LOGE("%s", last_error_.c_str());
                return ChannelError::SEND_FAILED;
            }
        } else if (n == 0) {
            last_error_ = "Peer closed";
            return ChannelError::PEER_CLOSED;
        }
        sent += n;
    }
    return ChannelError::SUCCESS;
}

ChannelError ControlChannel::receiveBytes(void* data, size_t size) {
    uint8_t* ptr = static_cast<uint8_t*>(data);
    size_t received = 0;
    while (received < size) {
        ssize_t n = recv(socket_fd_, ptr + received, size - received, 0);
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                last_error_ = "Receive timeout";
                return ChannelError::TIMEOUT;
            } else if (errno == ECONNRESET) {
                last_error_ = "Peer closed";
                return ChannelError::PEER_CLOSED;
            } else {
                last_error_ = std::string("recv() failed: ") + strerror(errno);
                LOGE("%s", last_error_.c_str());
                return ChannelError::RECEIVE_FAILED;
            }
        } else if (n == 0) {
            last_error_ = "Peer closed";
            return ChannelError::PEER_CLOSED;
        }
        received += n;
    }
    return ChannelError::SUCCESS;
}

ChannelError ControlChannel::sendFd(int fd) {
    struct msghdr msg;
    struct iovec iov;
    char buf[1] = {0};
    union {
        struct cmsghdr cm;
        char control[CMSG_SPACE(sizeof(int))];
    } control_un;
    iov.iov_base = buf;
    iov.iov_len = 1;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control_un.control;
    msg.msg_controllen = sizeof(control_un.control);
    struct cmsghdr* cmptr = CMSG_FIRSTHDR(&msg);
    cmptr->cmsg_len = CMSG_LEN(sizeof(int));
    cmptr->cmsg_level = SOL_SOCKET;
    cmptr->cmsg_type = SCM_RIGHTS;
    *((int*)CMSG_DATA(cmptr)) = fd;
    if (sendmsg(socket_fd_, &msg, 0) < 0) {
        last_error_ = std::string("sendmsg() failed: ") + strerror(errno);
        LOGE("%s", last_error_.c_str());
        return ChannelError::SEND_FAILED;
    }
    return ChannelError::SUCCESS;
}

ChannelError ControlChannel::receiveFd(int& fd) {
    struct msghdr msg;
    struct iovec iov;
    char buf[1];
    union {
        struct cmsghdr cm;
        char control[CMSG_SPACE(sizeof(int))];
    } control_un;
    iov.iov_base = buf;
    iov.iov_len = 1;
    memset(&msg, 0, sizeof(msg));
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control_un.control;
    msg.msg_controllen = sizeof(control_un.control);
    if (recvmsg(socket_fd_, &msg, 0) < 0) {
        last_error_ = std::string("recvmsg() failed: ") + strerror(errno);
        LOGE("%s", last_error_.c_str());
        return ChannelError::RECEIVE_FAILED;
    }
    struct cmsghdr* cmptr = CMSG_FIRSTHDR(&msg);
    if (cmptr == nullptr ||
        cmptr->cmsg_len != CMSG_LEN(sizeof(int)) ||
        cmptr->cmsg_level != SOL_SOCKET ||
        cmptr->cmsg_type != SCM_RIGHTS) {
        last_error_ = "Invalid control message";
        return ChannelError::INVALID_MESSAGE;
    }
    fd = *((int*)CMSG_DATA(cmptr));
    return ChannelError::SUCCESS;
}

bool ControlChannel::setSocketTimeout(int socket, uint32_t timeout_ms, bool is_send) {
    struct timeval tv;
    tv.tv_sec = timeout_ms / 1000;
    tv.tv_usec = (timeout_ms % 1000) * 1000;
    int opt = is_send ? SO_SNDTIMEO : SO_RCVTIMEO;
    if (setsockopt(socket, SOL_SOCKET, opt, &tv, sizeof(tv)) < 0) {
        LOGW("setsockopt(%s) failed: %s",
             is_send ? "SO_SNDTIMEO" : "SO_RCVTIMEO", strerror(errno));
        return false;
    }
    return true;
}

}  // namespace lsp
}  // namespace tinaide
