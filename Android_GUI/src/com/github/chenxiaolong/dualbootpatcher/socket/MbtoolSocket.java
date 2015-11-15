/*
 * Copyright (C) 2014  Andrew Gunnerson <andrewgunnerson@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.chenxiaolong.dualbootpatcher.socket;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.LocalSocketAddress.Namespace;
import android.os.Build;
import android.support.annotation.NonNull;
import android.util.Log;

import com.github.chenxiaolong.dualbootpatcher.CommandUtils;
import com.github.chenxiaolong.dualbootpatcher.RomUtils.RomInformation;
import com.github.chenxiaolong.dualbootpatcher.ThreadUtils;
import com.github.chenxiaolong.dualbootpatcher.Version;
import com.github.chenxiaolong.dualbootpatcher.Version.VersionParseException;
import com.github.chenxiaolong.dualbootpatcher.patcher.PatcherUtils;
import com.github.chenxiaolong.dualbootpatcher.socket.MbtoolUtils.Feature;
import com.github.chenxiaolong.dualbootpatcher.switcher.SwitcherUtils;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import mbtool.daemon.v3.FileChmodResponse;
import mbtool.daemon.v3.FileCloseResponse;
import mbtool.daemon.v3.FileOpenResponse;
import mbtool.daemon.v3.FileReadResponse;
import mbtool.daemon.v3.FileSELinuxGetLabelResponse;
import mbtool.daemon.v3.FileSELinuxSetLabelResponse;
import mbtool.daemon.v3.FileSeekResponse;
import mbtool.daemon.v3.FileStatResponse;
import mbtool.daemon.v3.FileWriteResponse;
import mbtool.daemon.v3.MbGetBootedRomIdRequest;
import mbtool.daemon.v3.MbGetBootedRomIdResponse;
import mbtool.daemon.v3.MbGetInstalledRomsRequest;
import mbtool.daemon.v3.MbGetInstalledRomsResponse;
import mbtool.daemon.v3.MbGetVersionRequest;
import mbtool.daemon.v3.MbGetVersionResponse;
import mbtool.daemon.v3.MbRom;
import mbtool.daemon.v3.MbSetKernelRequest;
import mbtool.daemon.v3.MbSetKernelResponse;
import mbtool.daemon.v3.MbSwitchRomRequest;
import mbtool.daemon.v3.MbSwitchRomResponse;
import mbtool.daemon.v3.MbSwitchRomResult;
import mbtool.daemon.v3.MbWipeRomRequest;
import mbtool.daemon.v3.MbWipeRomResponse;
import mbtool.daemon.v3.PathChmodRequest;
import mbtool.daemon.v3.PathChmodResponse;
import mbtool.daemon.v3.PathCopyRequest;
import mbtool.daemon.v3.PathCopyResponse;
import mbtool.daemon.v3.PathSELinuxGetLabelRequest;
import mbtool.daemon.v3.PathSELinuxGetLabelResponse;
import mbtool.daemon.v3.PathSELinuxSetLabelRequest;
import mbtool.daemon.v3.PathSELinuxSetLabelResponse;
import mbtool.daemon.v3.RebootRequest;
import mbtool.daemon.v3.RebootResponse;
import mbtool.daemon.v3.Request;
import mbtool.daemon.v3.RequestType;
import mbtool.daemon.v3.Response;
import mbtool.daemon.v3.ResponseType;

public class MbtoolSocket {
    private static final String TAG = MbtoolSocket.class.getSimpleName();

    private static final String SOCKET_ADDRESS = "mbtool.daemon";

    // Same as the C++ default
    private static final int FBB_SIZE = 1024;

    private static final String RESPONSE_ALLOW = "ALLOW";
    private static final String RESPONSE_DENY = "DENY";
    private static final String RESPONSE_OK = "OK";
    private static final String RESPONSE_UNSUPPORTED = "UNSUPPORTED";

    private static MbtoolSocket sInstance;

    private LocalSocket mSocket;
    private InputStream mSocketIS;
    private OutputStream mSocketOS;
    private int mInterfaceVersion;
    private String mMbtoolVersion;

    // Keep this as a singleton class for now
    private MbtoolSocket() {
        mInterfaceVersion = 3;
    }

    public static MbtoolSocket getInstance() {
        if (sInstance == null) {
            sInstance = new MbtoolSocket();
        }
        return sInstance;
    }

    /**
     * Check if mbtool rejected the connection due to the signature check failing
     *
     * @throws IOException Signature check failed or unexpected response
     */
    private void verifyCredentials() throws IOException {
        String response = SocketUtils.readString(mSocketIS);
        if (RESPONSE_DENY.equals(response)) {
            throw new IOException("mbtool explicitly denied access to the daemon. " +
                    "WARNING: This app is probably not officially signed!");
        } else if (!RESPONSE_ALLOW.equals(response)) {
            throw new IOException("Unexpected reply: " + response);
        }
    }

    /**
     * Request protocol version from mbtool
     *
     * @throws IOException Protocol version not supported or unexpected reply
     */
    private void requestInterfaceVersion() throws IOException {
        SocketUtils.writeInt32(mSocketOS, mInterfaceVersion);
        String response = SocketUtils.readString(mSocketIS);
        if (RESPONSE_UNSUPPORTED.equals(response)) {
            throw new IOException("Daemon does not support interface " + mInterfaceVersion);
        } else if (!RESPONSE_OK.equals(response)) {
            throw new IOException("Unexpected reply: " + response);
        }
    }

    /**
     * Check that the minimum mbtool version is satisfied
     *
     * @throws IOException Could not determine mbtool version, invalid mbtool version, or mbtool
     *                     version is too old
     */
    private void verifyMbtoolVersion() throws IOException {
        // Get mbtool version
        FlatBufferBuilder builder = new FlatBufferBuilder(FBB_SIZE);
        MbGetVersionRequest.startMbGetVersionRequest(builder);
        int fbRrequest = MbGetVersionRequest.endMbGetVersionRequest(builder);
        Request.startRequest(builder);
        Request.addRequestType(builder, RequestType.MbGetVersionRequest);
        Request.addRequest(builder, fbRrequest);
        builder.finish(Request.endRequest(builder));
        MbGetVersionResponse response = (MbGetVersionResponse)
                sendRequest(builder, ResponseType.MbGetVersionResponse);
        mMbtoolVersion = response.version();
        if (mMbtoolVersion == null) {
            throw new IOException("Could not determine mbtool version");
        }

        Version v1;
        Version v2 = MbtoolUtils.getMinimumRequiredVersion(Feature.DAEMON);

        try {
            v1 = new Version(mMbtoolVersion);
        } catch (VersionParseException e) {
            throw new IOException("Invalid version number: " + mMbtoolVersion);
        }

        Log.v(TAG, "mbtool version: " + v1);
        Log.v(TAG, "minimum version: " + v2);

        // Ensure that the version is newer than the minimum required version
        if (v1.compareTo(v2) < 0) {
            throw new IOException("mbtool version is: " + v1 + ", " +
                    "minimum needed is: " + v2);
        }
    }

    /**
     * Initializes the mbtool connection.
     *
     * 1. Setup input and output streams
     * 2. Check to make sure mbtool authorized our connection
     * 3. Request interface version and check if the daemon supports it
     * 4. Get mbtool version from daemon
     *
     * @throws IOException
     */
    private void initializeConnection() throws IOException {
        mSocketIS = mSocket.getInputStream();
        mSocketOS = mSocket.getOutputStream();

        verifyCredentials();
        requestInterfaceVersion();
        verifyMbtoolVersion();
    }

    /**
     * Connects to the mbtool socket
     *
     * 1. Try connecting to the socket
     * 2. If that fails or if the version is too old, launch bundled mbtool and connect again
     * 3. If that fails, then return false
     *
     * @param context Application context
     */
    public void connect(Context context) throws IOException {
        // If we're already connected, then we're good
        if (mSocket != null) {
            return;
        }

        // Try connecting to the socket
        try {
            mSocket = new LocalSocket();
            mSocket.connect(new LocalSocketAddress(SOCKET_ADDRESS, Namespace.ABSTRACT));
            initializeConnection();
            return;
        } catch (IOException e) {
            Log.e(TAG, "Could not connect to mbtool socket", e);
            disconnect();
        }

        Log.v(TAG, "Launching bundled mbtool");

        if (!executeMbtool(context)) {
            throw new IOException("Failed to execute mbtool");
        }

        // Give mbtool a little bit of time to start listening on the socket
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        try {
            mSocket = new LocalSocket();
            mSocket.connect(new LocalSocketAddress(SOCKET_ADDRESS, Namespace.ABSTRACT));
            initializeConnection();
        } catch (IOException e) {
            disconnect();
            throw new IOException("Could not connect to mbtool socket", e);
        }
    }

    @SuppressWarnings("deprecation")
    private boolean executeMbtool(Context context) {
        PatcherUtils.extractPatcher(context);
        String abi;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            abi = Build.SUPPORTED_ABIS[0];
        } else {
            abi = Build.CPU_ABI;
        }

        String mbtool = PatcherUtils.getTargetDirectory(context)
                + "/binaries/android/" + abi + "/mbtool";

        return CommandUtils.runRootCommand("mount -o remount,rw /") == 0
                && CommandUtils.runRootCommand("mv /mbtool /mbtool.bak || :") == 0
                && CommandUtils.runRootCommand("cp " + mbtool + " /mbtool") == 0
                && CommandUtils.runRootCommand("chmod 755 /mbtool") == 0
                && CommandUtils.runRootCommand("mount -o remount,ro / || :") == 0
                && CommandUtils.runRootCommand("/mbtool daemon --replace --daemonize") == 0;
    }

    public void disconnect() {
        Log.i(TAG, "Disconnecting from mbtool");
        IOUtils.closeQuietly(mSocket);
        IOUtils.closeQuietly(mSocketIS);
        IOUtils.closeQuietly(mSocketOS);
        mSocket = null;
        mSocketIS = null;
        mSocketOS = null;

        mMbtoolVersion = null;
    }

    /**
     * Get version of the mbtool daemon.
     *
     * @param context Application context
     * @return String containing the mbtool version
     * @throws IOException When any socket communication error occurs
     */
    @NonNull
    public String version(Context context) throws IOException {
        connect(context);

        return mMbtoolVersion;
    }

    /**
     * Get list of installed ROMs.
     *
     * NOTE: The list of ROMs will be re-queried and a new array will be returned every time this
     *       method is called. It may be a good idea to cache the results after the initial call.
     *
     * @param context Application context
     * @return Array of {@link RomInformation} objects representing the list of installed ROMs
     * @throws IOException When any socket communication error occurs
     */
    @NonNull
    public RomInformation[] getInstalledRoms(Context context) throws IOException {
        connect(context);

        try {
            // Create request
            FlatBufferBuilder builder = new FlatBufferBuilder(FBB_SIZE);
            MbGetInstalledRomsRequest.startMbGetInstalledRomsRequest(builder);
            // No parameters
            int fbRequest = MbGetInstalledRomsRequest.endMbGetInstalledRomsRequest(builder);

            // Wrap request
            Request.startRequest(builder);
            Request.addRequestType(builder, RequestType.MbGetInstalledRomsRequest);
            Request.addRequest(builder, fbRequest);
            builder.finish(Request.endRequest(builder));

            // Send request
            MbGetInstalledRomsResponse response = (MbGetInstalledRomsResponse)
                    sendRequest(builder, ResponseType.MbGetInstalledRomsResponse);

            RomInformation[] roms = new RomInformation[response.romsLength()];

            for (int i = 0; i < response.romsLength(); i++) {
                RomInformation rom = roms[i] = new RomInformation();
                MbRom fbrom = response.roms(i);

                rom.setId(fbrom.id());
                rom.setSystemPath(fbrom.systemPath());
                rom.setCachePath(fbrom.cachePath());
                rom.setDataPath(fbrom.dataPath());
                rom.setVersion(fbrom.version());
                rom.setBuild(fbrom.build());
            }

            return roms;
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    /**
     * Get the current ROM ID.
     *
     * @param context Application context
     * @return String containing the current ROM ID
     * @throws IOException When any socket communication error occurs
     */
    @NonNull
    public String getBootedRomId(Context context) throws IOException {
        connect(context);

        try {
            // Create request
            FlatBufferBuilder builder = new FlatBufferBuilder(FBB_SIZE);
            MbGetBootedRomIdRequest.startMbGetBootedRomIdRequest(builder);
            // No parameters
            int fbRequest = MbGetBootedRomIdRequest.endMbGetBootedRomIdRequest(builder);

            // Wrap request
            Request.startRequest(builder);
            Request.addRequestType(builder, RequestType.MbGetBootedRomIdRequest);
            Request.addRequest(builder, fbRequest);
            builder.finish(Request.endRequest(builder));

            // Send request
            MbGetBootedRomIdResponse response = (MbGetBootedRomIdResponse)
                    sendRequest(builder, ResponseType.MbGetBootedRomIdResponse);

            return response.romId();
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    public enum SwitchRomResult {
        UNKNOWN_BOOT_PARTITION,
        SUCCEEDED,
        FAILED,
        CHECKSUM_INVALID,
        CHECKSUM_NOT_FOUND
    }

    /**
     * Switch to another ROM.
     *
     * NOTE: If {@link SwitchRomResult#FAILED} is returned, there is no way of determining the cause
     *       of failure programmatically. However, mbtool will likely print debugging information
     *       (errno, etc.) to the logcat for manual reviewing.
     *
     * @param context Application context
     * @param id ID of ROM to switch to
     * @return {@link SwitchRomResult#UNKNOWN_BOOT_PARTITION} if the boot partition could not be determined
     *         {@link SwitchRomResult#SUCCEEDED} if the ROM was successfully switched
     *         {@link SwitchRomResult#FAILED} if the ROM failed to switch
     * @throws IOException When any socket communication error occurs
     */
    @NonNull
    public SwitchRomResult switchRom(Context context, String id,
                                     boolean forceChecksumsUpdate) throws IOException {
        connect(context);

        try {
            String bootBlockDev = SwitcherUtils.getBootPartition(context);
            if (bootBlockDev == null) {
                Log.e(TAG, "Failed to determine boot partition");
                return SwitchRomResult.UNKNOWN_BOOT_PARTITION;
            }

            // Create request
            FlatBufferBuilder builder = new FlatBufferBuilder(FBB_SIZE);
            int fbRomId = builder.createString(id);
            int fbBootBlockDev = builder.createString(bootBlockDev);

            // Blockdev search dirs
            String[] searchDirs = SwitcherUtils.getBlockDevSearchDirs(context);
            int fbSearchDirs = 0;
            if (searchDirs != null) {
                int[] searchDirsOffsets = new int[searchDirs.length];
                for (int i = 0; i < searchDirs.length; i++) {
                    searchDirsOffsets[i] = builder.createString(searchDirs[i]);
                }

                fbSearchDirs = MbSwitchRomRequest.createBlockdevBaseDirsVector(
                        builder, searchDirsOffsets);
            }

            MbSwitchRomRequest.startMbSwitchRomRequest(builder);
            MbSwitchRomRequest.addRomId(builder, fbRomId);
            MbSwitchRomRequest.addBootBlockdev(builder, fbBootBlockDev);
            MbSwitchRomRequest.addBlockdevBaseDirs(builder, fbSearchDirs);
            MbSwitchRomRequest.addForceUpdateChecksums(builder, forceChecksumsUpdate);
            int fbRequest = MbSwitchRomRequest.endMbSwitchRomRequest(builder);

            // Wrap request
            Request.startRequest(builder);
            Request.addRequestType(builder, RequestType.MbSwitchRomRequest);
            Request.addRequest(builder, fbRequest);
            builder.finish(Request.endRequest(builder));

            // Send request
            MbSwitchRomResponse response = (MbSwitchRomResponse)
                    sendRequest(builder, ResponseType.MbSwitchRomResponse);

            SwitchRomResult result;
            switch (response.result()) {
            case MbSwitchRomResult.SUCCEEDED:
                result = SwitchRomResult.SUCCEEDED;
                break;
            case MbSwitchRomResult.FAILED:
                result = SwitchRomResult.FAILED;
                break;
            case MbSwitchRomResult.CHECKSUM_INVALID:
                result = SwitchRomResult.CHECKSUM_INVALID;
                break;
            case MbSwitchRomResult.CHECKSUM_NOT_FOUND:
                result = SwitchRomResult.CHECKSUM_NOT_FOUND;
                break;
            default:
                throw new IOException("Invalid SwitchRomResult: " + response.result());
            }

            return result;
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    public enum SetKernelResult {
        UNKNOWN_BOOT_PARTITION,
        SUCCEEDED,
        FAILED
    }

    /**
     * Set the kernel for a ROM.
     *
     * NOTE: If {@link SetKernelResult#FAILED} is returned, there is no way of determining the cause
     *       of failure programmatically. However, mbtool will likely print debugging information
     *       (errno, etc.) to the logcat for manual reviewing.
     *
     * @param context Application context
     * @param id ID of ROM to set the kernel for
     * @return {@link SetKernelResult#UNKNOWN_BOOT_PARTITION} if the boot partition could not be determined
     *         {@link SetKernelResult#SUCCEEDED} if setting the kernel was successful
     *         {@link SetKernelResult#FAILED} if setting the kernel failed
     * @throws IOException When any socket communication error occurs
     */
    @NonNull
    public SetKernelResult setKernel(Context context, String id) throws IOException {
        connect(context);

        try {
            String bootBlockDev = SwitcherUtils.getBootPartition(context);
            if (bootBlockDev == null) {
                Log.e(TAG, "Failed to determine boot partition");
                return SetKernelResult.UNKNOWN_BOOT_PARTITION;
            }

            // Create request
            FlatBufferBuilder builder = new FlatBufferBuilder(FBB_SIZE);
            int fbRomId = builder.createString(id);
            int fbBootBlockDev = builder.createString(bootBlockDev);
            MbSetKernelRequest.startMbSetKernelRequest(builder);
            MbSetKernelRequest.addRomId(builder, fbRomId);
            MbSetKernelRequest.addBootBlockdev(builder, fbBootBlockDev);
            int fbRequest = MbSetKernelRequest.endMbSetKernelRequest(builder);

            // Wrap request
            Request.startRequest(builder);
            Request.addRequestType(builder, RequestType.MbSetKernelRequest);
            Request.addRequest(builder, fbRequest);
            builder.finish(Request.endRequest(builder));

            // Send request
            MbSetKernelResponse response = (MbSetKernelResponse)
                    sendRequest(builder, ResponseType.MbSetKernelResponse);

            return response.success() ? SetKernelResult.SUCCEEDED : SetKernelResult.FAILED;
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    /**
     * Reboots the device.
     *
     * @param context Application context
     * @param arg Reboot argument (eg. "recovery", "download", "bootloader"). Pass "" for a regular
     *            reboot.
     * @return True if the call to init succeeded and a reboot is pending. False, otherwise.
     * @throws IOException When any socket communication error occurs
     */
    public boolean restart(Context context, String arg) throws IOException {
        connect(context);

        try {
            // Create request
            FlatBufferBuilder builder = new FlatBufferBuilder(FBB_SIZE);
            int fbArg = builder.createString(arg != null ? arg : "");
            RebootRequest.startRebootRequest(builder);
            RebootRequest.addArg(builder, fbArg);
            int fbRequest = RebootRequest.endRebootRequest(builder);

            // Wrap request
            Request.startRequest(builder);
            Request.addRequestType(builder, RequestType.RebootRequest);
            Request.addRequest(builder, fbRequest);
            builder.finish(Request.endRequest(builder));

            // Send request
            RebootResponse response = (RebootResponse)
                    sendRequest(builder, ResponseType.RebootResponse);

            return response.success();
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    /**
     * Copy a file using mbtool.
     *
     * NOTE: If false is returned, there is no way of determining the cause of failure
     *       programmatically. However, mbtool will likely print debugging information (errno, etc.)
     *       to the logcat for manual reviewing.
     *
     * @param context Application context
     * @param source Absolute source path
     * @param target Absolute target path
     * @return True if the operation was successful. False, otherwise.
     * @throws IOException When any socket communication error occurs
     */
    public boolean pathCopy(Context context, String source, String target) throws IOException {
        connect(context);

        try {
            // Create request
            FlatBufferBuilder builder = new FlatBufferBuilder(FBB_SIZE);
            int fbSource = builder.createString(source);
            int fbTarget = builder.createString(target);
            PathCopyRequest.startPathCopyRequest(builder);
            PathCopyRequest.addSource(builder, fbSource);
            PathCopyRequest.addTarget(builder, fbTarget);
            int fbRequest = PathCopyRequest.endPathCopyRequest(builder);

            // Wrap request
            Request.startRequest(builder);
            Request.addRequestType(builder, RequestType.PathCopyRequest);
            Request.addRequest(builder, fbRequest);
            builder.finish(Request.endRequest(builder));

            // Send request
            PathCopyResponse response = (PathCopyResponse)
                    sendRequest(builder, ResponseType.PathCopyResponse);

            if (!response.success()) {
                Log.e(TAG, "Failed to copy from " + source + " to " + target + ": " +
                        response.errorMsg());
                return false;
            }

            return true;
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    /**
     * Chmod a file using mbtool.
     *
     * NOTE: If false is returned, there is no way of determining the cause of failure
     *       programmatically. However, mbtool will likely print debugging information (errno, etc.)
     *       to the logcat for manual reviewing.
     *
     * @param context Application context
     * @param filename Absolute path
     * @param mode Unix permissions number (will be AND'ed with 0777 by mbtool for security reasons)
     * @return True if the operation was successful. False, otherwise.
     * @throws IOException When any socket communication error occurs
     */
    public boolean pathChmod(Context context, String filename, int mode) throws IOException {
        connect(context);

        try {
            // Create request
            FlatBufferBuilder builder = new FlatBufferBuilder(FBB_SIZE);
            int fbFilename = builder.createString(filename);
            PathChmodRequest.startPathChmodRequest(builder);
            PathChmodRequest.addPath(builder, fbFilename);
            PathChmodRequest.addMode(builder, mode);
            int fbRequest = PathChmodRequest.endPathChmodRequest(builder);

            // Wrap request
            Request.startRequest(builder);
            Request.addRequestType(builder, RequestType.PathChmodRequest);
            Request.addRequest(builder, fbRequest);
            builder.finish(Request.endRequest(builder));

            // Send request
            PathChmodResponse response = (PathChmodResponse)
                    sendRequest(builder, ResponseType.PathChmodResponse);

            if (!response.success()) {
                Log.e(TAG, "Failed to chmod " + filename + ": " + response.errorMsg());
                return false;
            }

            return true;
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    public static class WipeResult {
        // Targets as listed in WipeTarget
        public short[] succeeded;
        public short[] failed;
    }

    /**
     * Wipe a ROM.
     *
     * @param context Application context
     * @param romId ROM ID to wipe
     * @param targets List of {@link mbtool.daemon.v3.MbWipeTarget}s indicating the wipe targets
     * @return {@link WipeResult} containing the list of succeeded and failed wipe targets
     * @throws IOException When any socket communication error occurs
     */
    @NonNull
    public WipeResult wipeRom(Context context, String romId, short[] targets) throws IOException {
        connect(context);

        try {
            // Create request
            FlatBufferBuilder builder = new FlatBufferBuilder(FBB_SIZE);
            int fbRomId = builder.createString(romId);
            int fbTargets = MbWipeRomRequest.createTargetsVector(builder, targets);
            MbWipeRomRequest.startMbWipeRomRequest(builder);
            MbWipeRomRequest.addRomId(builder, fbRomId);
            MbWipeRomRequest.addTargets(builder, fbTargets);
            int fbRequest = MbWipeRomRequest.endMbWipeRomRequest(builder);

            // Wrap request
            Request.startRequest(builder);
            Request.addRequestType(builder, RequestType.MbWipeRomRequest);
            Request.addRequest(builder, fbRequest);
            builder.finish(Request.endRequest(builder));

            // Send request
            MbWipeRomResponse response = (MbWipeRomResponse)
                    sendRequest(builder, ResponseType.MbWipeRomResponse);

            WipeResult result = new WipeResult();
            result.succeeded = new short[response.succeededLength()];
            result.failed = new short[response.failedLength()];

            for (int i = 0; i < response.succeededLength(); i++) {
                result.succeeded[i] = response.succeeded(i);
            }
            for (int i = 0; i < response.failedLength(); i++) {
                result.failed[i] = response.failed(i);
            }

            return result;
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    /**
     * Get the SELinux label of a path.
     *
     * NOTE: If false is returned, there is no way of determining the cause of failure
     *       programmatically. However, mbtool will likely print debugging information (errno, etc.)
     *       to the logcat for manual reviewing.
     *
     * @param context Application context
     * @param path Absolute path
     * @param followSymlinks Whether to follow symlinks
     * @return SELinux label if it was successfully retrieved. False, otherwise.
     * @throws IOException When any socket communication error occurs
     */
    public String pathSelinuxGetLabel(Context context, String path,
                                      boolean followSymlinks) throws IOException {
        connect(context);

        try {
            // Create request
            FlatBufferBuilder builder = new FlatBufferBuilder(FBB_SIZE);
            int fbPath = builder.createString(path);
            PathSELinuxGetLabelRequest.startPathSELinuxGetLabelRequest(builder);
            PathSELinuxGetLabelRequest.addPath(builder, fbPath);
            PathSELinuxGetLabelRequest.addFollowSymlinks(builder, followSymlinks);
            int fbRequest = PathSELinuxGetLabelRequest.endPathSELinuxGetLabelRequest(builder);

            // Wrap request
            Request.startRequest(builder);
            Request.addRequestType(builder, RequestType.PathSELinuxGetLabelRequest);
            Request.addRequest(builder, fbRequest);
            builder.finish(Request.endRequest(builder));

            // Send request
            PathSELinuxGetLabelResponse response = (PathSELinuxGetLabelResponse)
                    sendRequest(builder, ResponseType.PathSELinuxGetLabelResponse);

            if (!response.success()) {
                Log.e(TAG, "Failed to get SELinux label for " + path + ": " + response.errorMsg());
                return null;
            }

            return response.label();
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    /**
     * Set the SELinux label for a path.
     *
     * NOTE: If false is returned, there is no way of determining the cause of failure
     *       programmatically. However, mbtool will likely print debugging information (errno, etc.)
     *       to the logcat for manual reviewing.
     *
     * @param context Application context
     * @param path Absolute path
     * @param label SELinux label
     * @param followSymlinks Whether to follow symlinks
     * @return True if the SELinux label was successfully set. False, otherwise.
     * @throws IOException When any socket communication error occurs
     */
    public boolean pathSelinuxSetLabel(Context context, String path, String label,
                                       boolean followSymlinks) throws IOException {
        connect(context);

        try {
            // Create request
            FlatBufferBuilder builder = new FlatBufferBuilder(FBB_SIZE);
            int fbPath = builder.createString(path);
            int fbLabel = builder.createString(label);
            PathSELinuxSetLabelRequest.startPathSELinuxSetLabelRequest(builder);
            PathSELinuxSetLabelRequest.addPath(builder, fbPath);
            PathSELinuxSetLabelRequest.addLabel(builder, fbLabel);
            PathSELinuxSetLabelRequest.addFollowSymlinks(builder, followSymlinks);
            int fbRequest = PathSELinuxSetLabelRequest.endPathSELinuxSetLabelRequest(builder);

            // Wrap request
            Request.startRequest(builder);
            Request.addRequestType(builder, RequestType.PathSELinuxSetLabelRequest);
            Request.addRequest(builder, fbRequest);
            builder.finish(Request.endRequest(builder));

            // Send request
            PathSELinuxSetLabelResponse response = (PathSELinuxSetLabelResponse)
                    sendRequest(builder, ResponseType.PathSELinuxSetLabelResponse);

            if (!response.success()) {
                Log.e(TAG, "Failed to set SELinux label for " + path + ": " + response.errorMsg());
                return false;
            }

            return true;
        } catch (IOException e) {
            disconnect();
            throw e;
        }
    }

    // Private helper functions

    @NonNull
    private Table sendRequest(FlatBufferBuilder builder, byte expected) throws IOException {
        ThreadUtils.enforceExecutionOnNonMainThread();

        SocketUtils.writeBytes(mSocketOS, builder.sizedByteArray());

        byte[] responseBytes = SocketUtils.readBytes(mSocketIS);
        ByteBuffer bb = ByteBuffer.wrap(responseBytes);
        Response response = Response.getRootAsResponse(bb);

        if (response.responseType() == ResponseType.Unsupported) {
            throw new IOException("Unsupported command");
        } else if (response.responseType() == ResponseType.Invalid) {
            throw new IOException("Invalid command request");
        } else if (response.responseType() != expected) {
            throw new IOException("Unexpected response type");
        }

        Table table;

        switch (response.responseType()) {
        case ResponseType.FileChmodResponse:
            table = new FileChmodResponse();
            break;
        case ResponseType.FileCloseResponse:
            table = new FileCloseResponse();
            break;
        case ResponseType.FileOpenResponse:
            table = new FileOpenResponse();
            break;
        case ResponseType.FileReadResponse:
            table = new FileReadResponse();
            break;
        case ResponseType.FileSeekResponse:
            table = new FileSeekResponse();
            break;
        case ResponseType.FileStatResponse:
            table = new FileStatResponse();
            break;
        case ResponseType.FileWriteResponse:
            table = new FileWriteResponse();
            break;
        case ResponseType.FileSELinuxGetLabelResponse:
            table = new FileSELinuxGetLabelResponse();
            break;
        case ResponseType.FileSELinuxSetLabelResponse:
            table = new FileSELinuxSetLabelResponse();
            break;
        case ResponseType.PathChmodResponse:
            table = new PathChmodResponse();
            break;
        case ResponseType.PathCopyResponse:
            table = new PathCopyResponse();
            break;
        case ResponseType.PathSELinuxGetLabelResponse:
            table = new PathSELinuxGetLabelResponse();
            break;
        case ResponseType.PathSELinuxSetLabelResponse:
            table = new PathSELinuxSetLabelResponse();
            break;
        case ResponseType.MbGetVersionResponse:
            table = new MbGetVersionResponse();
            break;
        case ResponseType.MbGetInstalledRomsResponse:
            table = new MbGetInstalledRomsResponse();
            break;
        case ResponseType.MbGetBootedRomIdResponse:
            table = new MbGetBootedRomIdResponse();
            break;
        case ResponseType.MbSwitchRomResponse:
            table = new MbSwitchRomResponse();
            break;
        case ResponseType.MbSetKernelResponse:
            table = new MbSetKernelResponse();
            break;
        case ResponseType.MbWipeRomResponse:
            table = new MbWipeRomResponse();
            break;
        case ResponseType.RebootResponse:
            table = new RebootResponse();
            break;
        default:
            throw new IOException("Invalid response type");
        }

        Table ret = response.response(table);

        if (ret == null) {
            throw new IOException("Invalid union data");
        }

        return ret;
    }
}
