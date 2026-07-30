cmake_minimum_required(VERSION 3.4.1)
project(monerujo)
message(STATUS ABI_INFO  = ${ANDROID_ABI})
set(CMAKE_VERBOSE_MAKEFILE ON)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
add_library( monerujo
             SHARED
             src/main/cpp/monerujo.cpp )

set(EXTERNAL_LIBS_DIR ${CMAKE_SOURCE_DIR}/external-libs)
set(TREZOR_LIBS_DIR ${EXTERNAL_LIBS_DIR}/trezor)
set(ARCHIVE_LIBS_DIR ${EXTERNAL_LIBS_DIR}/${ANDROID_ABI})
set(TREZOR_ARCHIVE_LIBS_DIR ${TREZOR_LIBS_DIR}/${ANDROID_ABI})
set(EXTERNAL_INCLUDE_DIR ${TREZOR_LIBS_DIR}/include)
set(MONERO_ARCHIVE_LIBS_DIR ${ARCHIVE_LIBS_DIR}/monero)
set(TREZOR_MONERO_ARCHIVE_LIBS_DIR ${TREZOR_ARCHIVE_LIBS_DIR}/monero)

############
# libsodium
############

add_library(sodium STATIC IMPORTED)
set_target_properties(sodium PROPERTIES IMPORTED_LOCATION
        ${ARCHIVE_LIBS_DIR}/libsodium.a)

############
# OpenSSL
############

add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION
        ${ARCHIVE_LIBS_DIR}/libcrypto.a)

add_library(ssl STATIC IMPORTED)
set_target_properties(ssl PROPERTIES IMPORTED_LOCATION
    ${ARCHIVE_LIBS_DIR}/libssl.a)

############
# Boost
############

add_library(boost_chrono STATIC IMPORTED)
set_target_properties(boost_chrono PROPERTIES IMPORTED_LOCATION
    ${ARCHIVE_LIBS_DIR}/libboost_chrono.a)

add_library(boost_date_time STATIC IMPORTED)
set_target_properties(boost_date_time PROPERTIES IMPORTED_LOCATION
    ${ARCHIVE_LIBS_DIR}/libboost_date_time.a)

add_library(boost_filesystem STATIC IMPORTED)
set_target_properties(boost_filesystem PROPERTIES IMPORTED_LOCATION
    ${ARCHIVE_LIBS_DIR}/libboost_filesystem.a)

add_library(boost_program_options STATIC IMPORTED)
set_target_properties(boost_program_options PROPERTIES IMPORTED_LOCATION
    ${ARCHIVE_LIBS_DIR}/libboost_program_options.a)

add_library(boost_regex STATIC IMPORTED)
set_target_properties(boost_regex PROPERTIES IMPORTED_LOCATION
    ${ARCHIVE_LIBS_DIR}/libboost_regex.a)

add_library(boost_serialization STATIC IMPORTED)
set_target_properties(boost_serialization PROPERTIES IMPORTED_LOCATION
    ${ARCHIVE_LIBS_DIR}/libboost_serialization.a)

add_library(boost_system STATIC IMPORTED)
set_target_properties(boost_system PROPERTIES IMPORTED_LOCATION
    ${ARCHIVE_LIBS_DIR}/libboost_system.a)

add_library(boost_thread STATIC IMPORTED)
set_target_properties(boost_thread PROPERTIES IMPORTED_LOCATION
    ${ARCHIVE_LIBS_DIR}/libboost_thread.a)

add_library(boost_wserialization STATIC IMPORTED)
set_target_properties(boost_wserialization PROPERTIES IMPORTED_LOCATION
    ${ARCHIVE_LIBS_DIR}/libboost_wserialization.a)

#############
# Monero
#############

add_library(wallet_api STATIC IMPORTED)
set_target_properties(wallet_api PROPERTIES IMPORTED_LOCATION
    ${TREZOR_MONERO_ARCHIVE_LIBS_DIR}/libwallet_api.a)

add_library(wallet STATIC IMPORTED)
set_target_properties(wallet PROPERTIES IMPORTED_LOCATION
    ${TREZOR_MONERO_ARCHIVE_LIBS_DIR}/libwallet.a)

add_library(cryptonote_core STATIC IMPORTED)
set_target_properties(cryptonote_core PROPERTIES IMPORTED_LOCATION
    ${MONERO_ARCHIVE_LIBS_DIR}/libcryptonote_core.a)

add_library(cryptonote_basic STATIC IMPORTED)
set_target_properties(cryptonote_basic PROPERTIES IMPORTED_LOCATION
    ${TREZOR_MONERO_ARCHIVE_LIBS_DIR}/libcryptonote_basic.a)

add_library(mnemonics STATIC IMPORTED)
set_target_properties(mnemonics PROPERTIES IMPORTED_LOCATION
    ${MONERO_ARCHIVE_LIBS_DIR}/libmnemonics.a)

add_library(common STATIC IMPORTED)
set_target_properties(common PROPERTIES IMPORTED_LOCATION
    ${MONERO_ARCHIVE_LIBS_DIR}/libcommon.a)

add_library(cncrypto STATIC IMPORTED)
set_target_properties(cncrypto PROPERTIES IMPORTED_LOCATION
    ${MONERO_ARCHIVE_LIBS_DIR}/libcncrypto.a)

add_library(ringct STATIC IMPORTED)
set_target_properties(ringct PROPERTIES IMPORTED_LOCATION
    ${MONERO_ARCHIVE_LIBS_DIR}/libringct.a)

add_library(ringct_basic STATIC IMPORTED)
set_target_properties(ringct_basic PROPERTIES IMPORTED_LOCATION
    ${MONERO_ARCHIVE_LIBS_DIR}/libringct_basic.a)

add_library(blockchain_db STATIC IMPORTED)
set_target_properties(blockchain_db PROPERTIES IMPORTED_LOCATION
    ${MONERO_ARCHIVE_LIBS_DIR}/libblockchain_db.a)

add_library(lmdb STATIC IMPORTED)
set_target_properties(lmdb PROPERTIES IMPORTED_LOCATION
    ${MONERO_ARCHIVE_LIBS_DIR}/liblmdb.a)

add_library(easylogging STATIC IMPORTED)
set_target_properties(easylogging PROPERTIES IMPORTED_LOCATION
    ${MONERO_ARCHIVE_LIBS_DIR}/libeasylogging.a)

add_library(unbound STATIC IMPORTED)
set_target_properties(unbound PROPERTIES IMPORTED_LOCATION
    ${ARCHIVE_LIBS_DIR}/libunbound.a)

add_library(epee STATIC IMPORTED)
set_target_properties(epee PROPERTIES IMPORTED_LOCATION
    ${MONERO_ARCHIVE_LIBS_DIR}/libepee.a)

add_library(blocks STATIC IMPORTED)
set_target_properties(blocks PROPERTIES IMPORTED_LOCATION
    ${MONERO_ARCHIVE_LIBS_DIR}/libblocks.a)

add_library(checkpoints STATIC IMPORTED)
set_target_properties(checkpoints PROPERTIES IMPORTED_LOCATION
    ${MONERO_ARCHIVE_LIBS_DIR}/libcheckpoints.a)

add_library(device STATIC IMPORTED)
set_target_properties(device PROPERTIES IMPORTED_LOCATION
        ${TREZOR_MONERO_ARCHIVE_LIBS_DIR}/libdevice.a)

add_library(device_trezor STATIC IMPORTED)
set(device_trezor_path ${TREZOR_MONERO_ARCHIVE_LIBS_DIR}/libdevice_trezor.a)
set_target_properties(device_trezor PROPERTIES IMPORTED_LOCATION ${device_trezor_path})
if(NOT EXISTS ${device_trezor_path})
    message(FATAL_ERROR "Monero hardware-wallet support requires libdevice_trezor.a for ${ANDROID_ABI}")
endif()
file(SIZE ${device_trezor_path} device_trezor_size)
if(device_trezor_size LESS 100000)
    message(FATAL_ERROR "Monero hardware-wallet support requires the real device_trezor archive for ${ANDROID_ABI}")
endif()

set(protobuf_path ${TREZOR_ARCHIVE_LIBS_DIR}/libprotobuf.a)
if(NOT EXISTS ${protobuf_path})
    message(FATAL_ERROR "Monero hardware-wallet support requires libprotobuf.a for ${ANDROID_ABI}")
endif()
add_library(protobuf STATIC IMPORTED)
set_target_properties(protobuf PROPERTIES IMPORTED_LOCATION ${protobuf_path})

add_library(multisig STATIC IMPORTED)
set_target_properties(multisig PROPERTIES IMPORTED_LOCATION
    ${MONERO_ARCHIVE_LIBS_DIR}/libmultisig.a)

add_library(version STATIC IMPORTED)
set_target_properties(version PROPERTIES IMPORTED_LOCATION
    ${MONERO_ARCHIVE_LIBS_DIR}/libversion.a)

add_library(net STATIC IMPORTED)
set_target_properties(net PROPERTIES IMPORTED_LOCATION
        ${MONERO_ARCHIVE_LIBS_DIR}/libnet.a)

add_library(hardforks STATIC IMPORTED)
set_target_properties(hardforks PROPERTIES IMPORTED_LOCATION
        ${MONERO_ARCHIVE_LIBS_DIR}/libhardforks.a)

add_library(randomx STATIC IMPORTED)
set_target_properties(randomx PROPERTIES IMPORTED_LOCATION
        ${MONERO_ARCHIVE_LIBS_DIR}/librandomx.a)

add_library(rpc_base STATIC IMPORTED)
set_target_properties(rpc_base PROPERTIES IMPORTED_LOCATION
        ${MONERO_ARCHIVE_LIBS_DIR}/librpc_base.a)

add_library(wallet-crypto STATIC IMPORTED)
set_target_properties(wallet-crypto PROPERTIES IMPORTED_LOCATION
        ${MONERO_ARCHIVE_LIBS_DIR}/libwallet-crypto.a)

add_library(cryptonote_format_utils_basic STATIC IMPORTED)
set_target_properties(cryptonote_format_utils_basic PROPERTIES IMPORTED_LOCATION
        ${MONERO_ARCHIVE_LIBS_DIR}/libcryptonote_format_utils_basic.a)

#############
# System
#############

find_library( log-lib log )

include_directories( ${EXTERNAL_INCLUDE_DIR} )

message(STATUS EXTERNAL_LIBS_DIR :  ${EXTERNAL_LIBS_DIR})

if(${ANDROID_ABI} STREQUAL "x86_64")
    set(EXTRA_LIBS "wallet-crypto")
else()
    set(EXTRA_LIBS "")
endif()

target_link_libraries(  monerujo

                        wallet_api
                        wallet
                        cryptonote_core
                        cryptonote_basic
                        cryptonote_format_utils_basic
                        mnemonics
                        ringct
                        ringct_basic
                        net
                        common
                        cncrypto
                        blockchain_db
                        lmdb
                        easylogging
                        unbound
                        epee
                        blocks
                        checkpoints
                        device
                        device_trezor
                        protobuf
                        multisig
                        version
                        randomx
                        hardforks
                        rpc_base
                        ${EXTRA_LIBS}

                        boost_chrono
                        boost_date_time
                        boost_filesystem
                        boost_program_options
                        boost_regex
                        boost_serialization
                        boost_system
                        boost_thread
                        boost_wserialization

                        ssl
                        crypto

                        sodium

                       ${log-lib}
                       )
