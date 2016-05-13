// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.domain.registry.tools;

import com.google.domain.registry.tools.server.ListHostsAction;

/**
 * Unit tests for {@link ListHostsCommand}.
 *
 * @see ListObjectsCommandTestCase
 */
public class ListHostsCommandTest extends ListObjectsCommandTestCase<ListHostsCommand> {

  @Override
  final String getTaskPath() {
    return ListHostsAction.PATH;
  }

  @Override
  final String getTld() {
    return null;
  }
}