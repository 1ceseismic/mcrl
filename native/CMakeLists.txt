cmake_minimum_required(VERSION 3.20)
project(mcrl_native CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()
option(MCRL_ASAN "Build with AddressSanitizer + UBSan" OFF)
if(MCRL_ASAN)
  set(CMAKE_CXX_FLAGS_RELEASE "-O2")
else()
  # A RocketSim or Bullet assert would abort the game.
  set(CMAKE_CXX_FLAGS_RELEASE "-O2 -DNDEBUG")
endif()
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

if(MCRL_ASAN)
  add_compile_options(-fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer)
  add_link_options(-fsanitize=address,undefined)
endif()

add_subdirectory(third_party/RocketSim EXCLUDE_FROM_ALL)
target_include_directories(RocketSim PUBLIC
  ${CMAKE_CURRENT_SOURCE_DIR}/third_party/RocketSim/src
  ${CMAKE_CURRENT_SOURCE_DIR}/third_party/RocketSim/libsrc/bullet3-3.24)
target_compile_options(RocketSim PRIVATE -w)
if(MCRL_ASAN)
  # UBSan suppressions only work on recoverable checks.
  target_compile_options(RocketSim PRIVATE -fsanitize-recover=undefined)
endif()

set(MCRL_CORE_SOURCES src/abi.cpp src/world.cpp src/shapes.cpp src/mesher.cpp src/edgeinfo.cpp src/terrain.cpp
  src/voxels.cpp src/stepfloor.cpp src/stepassist.cpp src/guard.cpp src/fxfeed.cpp src/soccar.cpp src/materials.cpp
  src/fluids.cpp src/bot.cpp src/blob.cpp)
# -O3 vectorises the policy; no -march, for portable binaries.
set_source_files_properties(src/bot.cpp PROPERTIES COMPILE_OPTIONS -O3)
add_library(mcrl_core OBJECT ${MCRL_CORE_SOURCES})
target_include_directories(mcrl_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(mcrl_core PUBLIC RocketSim)

add_library(mcrl_rs SHARED $<TARGET_OBJECTS:mcrl_core>)
target_link_libraries(mcrl_rs PUBLIC mcrl_core)
set_target_properties(mcrl_rs PROPERTIES
  LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}
  OUTPUT_NAME mcrl_rs)

option(MCRL_BUILD_TESTS "Build the native tests, on a copy of the core with MCRL_TEST_HOOKS" ON)
if(MCRL_BUILD_TESTS)
  enable_testing()
  include(FetchContent)
  FetchContent_Declare(doctest
    GIT_REPOSITORY https://github.com/doctest/doctest.git
    GIT_TAG v2.4.11)
  FetchContent_GetProperties(doctest)
  if(NOT doctest_POPULATED)
    FetchContent_Populate(doctest)
  endif()

  add_library(mcrl_core_tests OBJECT ${MCRL_CORE_SOURCES})
  target_compile_definitions(mcrl_core_tests PUBLIC MCRL_TEST_HOOKS)
  target_include_directories(mcrl_core_tests PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
  target_link_libraries(mcrl_core_tests PUBLIC RocketSim)

  add_executable(mcrl_tests tests/test_abi.cpp tests/test_coords.cpp tests/test_world.cpp tests/test_mesher.cpp
    tests/test_terrain.cpp tests/test_physics.cpp tests/test_seams.cpp tests/test_origin.cpp
    tests/test_readiness.cpp tests/test_events.cpp tests/test_voxels.cpp tests/test_stepfloor.cpp
    tests/test_stepup.cpp tests/test_guard.cpp tests/test_fxfeed.cpp tests/test_cars.cpp
    tests/test_demos.cpp tests/test_soccar.cpp tests/test_materials.cpp
    tests/test_fluids.cpp tests/test_bot.cpp tests/test_predict.cpp)
  target_include_directories(mcrl_tests PRIVATE ${doctest_SOURCE_DIR} ${CMAKE_CURRENT_SOURCE_DIR}/src)
  target_link_libraries(mcrl_tests PRIVATE mcrl_core_tests)
  add_test(NAME mcrl_tests COMMAND mcrl_tests)
  if(MCRL_ASAN)
    set_tests_properties(mcrl_tests PROPERTIES ENVIRONMENT
      "UBSAN_OPTIONS=suppressions=${CMAKE_CURRENT_SOURCE_DIR}/tests/ubsan.supp:print_stacktrace=1:halt_on_error=1")
  endif()
endif()
