// Vulkan 1.1 host code for the SHA-256 benchmark kernel (sha256.comp).
// Links against NDK libvulkan.so at runtime via the Vulkan loader
// headers; no GPU mining, no stratum, no share submission.
//
// Buffers are HOST_VISIBLE | HOST_COHERENT (zero-copy unified memory
// on this SoC class; on other devices the same flags are requested and
// a failure is reported, not silently replaced with a different path).
// Element 0 of the input is always the padded "abc" SHA-256 block, so
// the kernel's correctness is checked on-device against the published
// digest ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
// before any nonce-block result is reported. If element 0 mismatches,
// the function returns TEST FAIL and no nonce hash is presented as valid.

#include <jni.h>
#include <android/log.h>
#include <vulkan/vulkan.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "sha256_spv.h"

#define LOG_TAG "anvil_vulkan"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static std::string hex_words(const uint32_t* w, int n) {
    static const char* hexd = "0123456789abcdef";
    std::string s;
    for (int i = 0; i < n; i++) {
        for (int shift = 28; shift >= 0; shift -= 4) s.push_back(hexd[(w[i] >> shift) & 0xf]);
    }
    return s;
}

static bool find_memory(VkPhysicalDevice pd, uint32_t type_bits, VkMemoryPropertyFlags props, uint32_t* out) {
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(pd, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++) {
        if ((type_bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & props) == props) {
            *out = i;
            return true;
        }
    }
    return false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_j3r0nim0_anvil_NativeBench_nativeVulkanSha256(JNIEnv* env, jclass, jint count_j) {
    int count = count_j < 1 ? 1 : (count_j > 65536 ? 65536 : (int)count_j);
    int total = count + 1;  // element 0 = "abc" test block
    // Pad to a whole number of 64-thread workgroups so dispatched
    // invocations never read past the input buffer.
    int padded = ((total + 63) / 64) * 64;
    std::string result;
    VkInstance instance = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkBuffer in_buf = VK_NULL_HANDLE, out_buf = VK_NULL_HANDLE;
    VkDeviceMemory in_mem = VK_NULL_HANDLE, out_mem = VK_NULL_HANDLE;
    VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
    VkDescriptorPool pool = VK_NULL_HANDLE;
    VkDescriptorSet dset = VK_NULL_HANDLE;
    VkPipelineLayout playout = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkShaderModule shader = VK_NULL_HANDLE;
    VkCommandPool cmd_pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    uint32_t pd_count = 0;
    uint32_t pd_count_local = 0;
    char device_name[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE] = {0};

    auto fail = [&](const char* stage, VkResult r) {
        char buf[256];
        snprintf(buf, sizeof(buf), "GPU bench FAIL at %s (VkResult=%d)", stage, (int)r);
        result = buf;
    };

    VkApplicationInfo app_info{};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ici{};
    ici.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    ici.pApplicationInfo = &app_info;
    VkResult r = vkCreateInstance(&ici, nullptr, &instance);
    if (r != VK_SUCCESS) { fail("vkCreateInstance", r); goto done; }

    r = vkEnumeratePhysicalDevices(instance, &pd_count_local, nullptr);
    pd_count = pd_count_local;
    if (r != VK_SUCCESS || pd_count == 0) { fail("vkEnumeratePhysicalDevices", r); goto done; }
    {
        std::vector<VkPhysicalDevice> pds(pd_count);
        vkEnumeratePhysicalDevices(instance, &pd_count, pds.data());
        VkPhysicalDevice pd = pds[0];
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(pd, &props);
        strncpy(device_name, props.deviceName, sizeof(device_name) - 1);
        uint32_t qf_count = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(pd, &qf_count, nullptr);
        std::vector<VkQueueFamilyProperties> qfs(qf_count);
        vkGetPhysicalDeviceQueueFamilyProperties(pd, &qf_count, qfs.data());
        uint32_t qf = UINT32_MAX;
        for (uint32_t i = 0; i < qf_count; i++) {
            if (qfs[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { qf = i; break; }
        }
        if (qf == UINT32_MAX) { fail("no compute queue family", VK_ERROR_INITIALIZATION_FAILED); goto done; }
        float prio = 1.0f;
        VkDeviceQueueCreateInfo qci{};
        qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
        qci.queueFamilyIndex = qf;
        qci.queueCount = 1;
        qci.pQueuePriorities = &prio;
        VkDeviceCreateInfo dci{};
        dci.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        dci.queueCreateInfoCount = 1;
        dci.pQueueCreateInfos = &qci;
        r = vkCreateDevice(pd, &dci, nullptr, &device);
        if (r != VK_SUCCESS) { fail("vkCreateDevice", r); goto done; }
        VkQueue queue = VK_NULL_HANDLE;
        vkGetDeviceQueue(device, qf, 0, &queue);

        VkDeviceSize in_size = (VkDeviceSize)padded * 64;
        VkDeviceSize out_size = (VkDeviceSize)padded * 32;
        auto make_buffer = [&](VkDeviceSize size, VkBuffer* buf, VkDeviceMemory* mem) -> VkResult {
            VkBufferCreateInfo bci{};
            bci.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
            bci.size = size;
            bci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
            bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
            VkResult rr = vkCreateBuffer(device, &bci, nullptr, buf);
            if (rr != VK_SUCCESS) return rr;
            VkMemoryRequirements mr{};
            vkGetBufferMemoryRequirements(device, *buf, &mr);
            uint32_t mi = 0;
            if (!find_memory(pd, mr.memoryTypeBits,
                             VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, &mi))
                return VK_ERROR_OUT_OF_HOST_MEMORY;
            VkMemoryAllocateInfo mai{};
            mai.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
            mai.allocationSize = mr.size;
            mai.memoryTypeIndex = mi;
            rr = vkAllocateMemory(device, &mai, nullptr, mem);
            if (rr != VK_SUCCESS) return rr;
            return vkBindBufferMemory(device, *buf, *mem, 0);
        };
        r = make_buffer(in_size, &in_buf, &in_mem);
        if (r != VK_SUCCESS) { fail("input buffer", r); goto done; }
        r = make_buffer(out_size, &out_buf, &out_mem);
        if (r != VK_SUCCESS) { fail("output buffer", r); goto done; }

        // Fill input: element 0 = padded "abc"; elements 1..count =
        // 4-byte nonce messages, padded the same way (0x80, length 32).
        {
            void* mapped = nullptr;
            r = vkMapMemory(device, in_mem, 0, in_size, 0, &mapped);
            if (r != VK_SUCCESS) { fail("map input", r); goto done; }
            auto* bytes = static_cast<uint8_t*>(mapped);
            memset(bytes, 0, (size_t)in_size);
            bytes[0] = 'a'; bytes[1] = 'b'; bytes[2] = 'c'; bytes[3] = 0x80; bytes[63] = 24;
            for (int i = 1; i < total; i++) {
                uint8_t* b = bytes + (size_t)i * 64;
                uint32_t nonce = (uint32_t)i;
                b[0] = (uint8_t)nonce; b[1] = (uint8_t)(nonce >> 8);
                b[2] = (uint8_t)(nonce >> 16); b[3] = (uint8_t)(nonce >> 24);
                b[4] = 0x80; b[63] = 32;
            }
            vkUnmapMemory(device, in_mem);
        }

        VkDescriptorSetLayoutBinding bindings[2]{};
        bindings[0].binding = 0; bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[0].descriptorCount = 1; bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        bindings[1].binding = 1; bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[1].descriptorCount = 1; bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        VkDescriptorSetLayoutCreateInfo dsli{};
        dsli.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        dsli.bindingCount = 2; dsli.pBindings = bindings;
        r = vkCreateDescriptorSetLayout(device, &dsli, nullptr, &dsl);
        if (r != VK_SUCCESS) { fail("descriptor layout", r); goto done; }
        VkDescriptorPoolSize psz{};
        psz.type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; psz.descriptorCount = 2;
        VkDescriptorPoolCreateInfo dpci{};
        dpci.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        dpci.maxSets = 1; dpci.poolSizeCount = 1; dpci.pPoolSizes = &psz;
        r = vkCreateDescriptorPool(device, &dpci, nullptr, &pool);
        if (r != VK_SUCCESS) { fail("descriptor pool", r); goto done; }
        VkDescriptorSetAllocateInfo dsai{};
        dsai.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
        dsai.descriptorPool = pool; dsai.descriptorSetCount = 1; dsai.pSetLayouts = &dsl;
        r = vkAllocateDescriptorSets(device, &dsai, &dset);
        if (r != VK_SUCCESS) { fail("descriptor set", r); goto done; }
        VkDescriptorBufferInfo dbi[2]{};
        dbi[0].buffer = in_buf; dbi[0].offset = 0; dbi[0].range = in_size;
        dbi[1].buffer = out_buf; dbi[1].offset = 0; dbi[1].range = out_size;
        VkWriteDescriptorSet writes[2]{};
        for (int i = 0; i < 2; i++) {
            writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[i].dstSet = dset; writes[i].dstBinding = (uint32_t)i;
            writes[i].descriptorCount = 1; writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            writes[i].pBufferInfo = &dbi[i];
        }
        vkUpdateDescriptorSets(device, 2, writes, 0, nullptr);

        VkPipelineLayoutCreateInfo plci{};
        plci.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        plci.setLayoutCount = 1; plci.pSetLayouts = &dsl;
        r = vkCreatePipelineLayout(device, &plci, nullptr, &playout);
        if (r != VK_SUCCESS) { fail("pipeline layout", r); goto done; }
        VkShaderModuleCreateInfo smci{};
        smci.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        smci.codeSize = sizeof(kSha256Spv);
        smci.pCode = kSha256Spv;
        r = vkCreateShaderModule(device, &smci, nullptr, &shader);
        if (r != VK_SUCCESS) { fail("shader module", r); goto done; }
        VkPipelineShaderStageCreateInfo stage{};
        stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = shader;
        stage.pName = "main";
        VkComputePipelineCreateInfo cpci{};
        cpci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        cpci.stage = stage; cpci.layout = playout;
        r = vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &cpci, nullptr, &pipeline);
        if (r != VK_SUCCESS) { fail("compute pipeline", r); goto done; }

        VkCommandPoolCreateInfo cpci2{};
        cpci2.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        cpci2.queueFamilyIndex = qf;
        r = vkCreateCommandPool(device, &cpci2, nullptr, &cmd_pool);
        if (r != VK_SUCCESS) { fail("command pool", r); goto done; }
        VkCommandBufferAllocateInfo cbai{};
        cbai.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        cbai.commandPool = cmd_pool; cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; cbai.commandBufferCount = 1;
        r = vkAllocateCommandBuffers(device, &cbai, &cmd);
        if (r != VK_SUCCESS) { fail("command buffer", r); goto done; }
        VkCommandBufferBeginInfo cbbi{};
        cbbi.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        r = vkBeginCommandBuffer(cmd, &cbbi);
        if (r != VK_SUCCESS) { fail("begin command buffer", r); goto done; }
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, playout, 0, 1, &dset, 0, nullptr);
        uint32_t groups = (uint32_t)(padded / 64);
        vkCmdDispatch(cmd, groups, 1, 1);
        r = vkEndCommandBuffer(cmd);
        if (r != VK_SUCCESS) { fail("end command buffer", r); goto done; }
        VkSubmitInfo si{};
        si.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
        si.commandBufferCount = 1; si.pCommandBuffers = &cmd;
        VkFence fence = VK_NULL_HANDLE;
        VkFenceCreateInfo fci{};
        fci.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        r = vkCreateFence(device, &fci, nullptr, &fence);
        if (r != VK_SUCCESS) { fail("fence", r); goto done; }
        r = vkQueueSubmit(queue, 1, &si, fence);
        if (r != VK_SUCCESS) { vkDestroyFence(device, fence, nullptr); fail("queue submit", r); goto done; }
        r = vkWaitForFences(device, 1, &fence, VK_TRUE, 10000000000ULL);
        vkDestroyFence(device, fence, nullptr);
        if (r != VK_SUCCESS) { fail("wait fences", r); goto done; }

        {
            void* mapped = nullptr;
            r = vkMapMemory(device, out_mem, 0, out_size, 0, &mapped);
            if (r != VK_SUCCESS) { fail("map output", r); goto done; }
            auto* words = static_cast<uint32_t*>(mapped);
            std::string test_hash = hex_words(words, 8);
            std::string first_nonce_hash = (total > 1) ? hex_words(words + 8, 8) : std::string("none");
            vkUnmapMemory(device, out_mem);
            const char* expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
            if (test_hash != expected) {
                char buf[512];
                snprintf(buf, sizeof(buf), "GPU SHA256 TEST FAIL got=%s expected=%s device=%s (kernel not trusted)",
                         test_hash.c_str(), expected, device_name);
                result = buf;
            } else {
                char buf[1024];
                snprintf(buf, sizeof(buf),
                         "GPU SHA256 OK blocks=%d first_nonce_hash=%s device=%s (benchmark only, no shares, no earnings)",
                         total, first_nonce_hash.c_str(), device_name);
                result = buf;
            }
        }
    }

done:
    if (cmd_pool != VK_NULL_HANDLE) vkDestroyCommandPool(device, cmd_pool, nullptr);
    if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, nullptr);
    if (shader != VK_NULL_HANDLE) vkDestroyShaderModule(device, shader, nullptr);
    if (playout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, playout, nullptr);
    if (pool != VK_NULL_HANDLE) vkDestroyDescriptorPool(device, pool, nullptr);
    if (dsl != VK_NULL_HANDLE) vkDestroyDescriptorSetLayout(device, dsl, nullptr);
    if (in_buf != VK_NULL_HANDLE) vkDestroyBuffer(device, in_buf, nullptr);
    if (out_buf != VK_NULL_HANDLE) vkDestroyBuffer(device, out_buf, nullptr);
    if (in_mem != VK_NULL_HANDLE) vkFreeMemory(device, in_mem, nullptr);
    if (out_mem != VK_NULL_HANDLE) vkFreeMemory(device, out_mem, nullptr);
    if (device != VK_NULL_HANDLE) vkDestroyDevice(device, nullptr);
    if (instance != VK_NULL_HANDLE) vkDestroyInstance(instance, nullptr);
    if (result.empty()) result = "GPU bench FAIL (unknown stage)";
    LOGI("%s", result.c_str());
    return env->NewStringUTF(result.c_str());
}
