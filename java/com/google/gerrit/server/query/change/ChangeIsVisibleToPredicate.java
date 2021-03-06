// Copyright (C) 2010 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.query.change;

import com.google.common.flogger.FluentLogger;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.index.query.IsVisibleToPredicate;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.AnonymousUser;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.index.IndexUtils;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.permissions.ChangePermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Provider;
import java.io.IOException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;

public class ChangeIsVisibleToPredicate extends IsVisibleToPredicate<ChangeData> {
  private static final FluentLogger logger = FluentLogger.forEnclosingClass();

  protected final Provider<ReviewDb> db;
  protected final ChangeNotes.Factory notesFactory;
  protected final CurrentUser user;
  protected final PermissionBackend permissionBackend;
  protected final ProjectCache projectCache;
  private final Provider<AnonymousUser> anonymousUserProvider;

  public ChangeIsVisibleToPredicate(
      Provider<ReviewDb> db,
      ChangeNotes.Factory notesFactory,
      CurrentUser user,
      PermissionBackend permissionBackend,
      ProjectCache projectCache,
      Provider<AnonymousUser> anonymousUserProvider) {
    super(ChangeQueryBuilder.FIELD_VISIBLETO, IndexUtils.describe(user));
    this.db = db;
    this.notesFactory = notesFactory;
    this.user = user;
    this.permissionBackend = permissionBackend;
    this.projectCache = projectCache;
    this.anonymousUserProvider = anonymousUserProvider;
  }

  @Override
  public boolean match(ChangeData cd) throws OrmException {
    if (cd.fastIsVisibleTo(user)) {
      return true;
    }
    Change change = cd.change();
    if (change == null) {
      return false;
    }

    ChangeNotes notes = notesFactory.createFromIndexedChange(change);

    try {
      ProjectState projectState = projectCache.checkedGet(cd.project());
      if (projectState == null) {
        logger.atInfo().log("No such project: %s", cd.project());
        return false;
      }
      if (!projectState.statePermitsRead()) {
        return false;
      }
    } catch (IOException e) {
      throw new OrmException("unable to read project state", e);
    }

    PermissionBackend.WithUser withUser =
        user.isIdentifiedUser()
            ? permissionBackend.absentUser(user.getAccountId())
            : permissionBackend.user(anonymousUserProvider.get());
    try {
      withUser.indexedChange(cd, notes).database(db).check(ChangePermission.READ);
    } catch (PermissionBackendException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RepositoryNotFoundException) {
        logger.atWarning().withCause(e).log(
            "Skipping change %s because the corresponding repository was not found", cd.getId());
        return false;
      }
      throw new OrmException("unable to check permissions on change " + cd.getId(), e);
    } catch (AuthException e) {
      return false;
    }

    cd.cacheVisibleTo(user);
    return true;
  }

  @Override
  public int getCost() {
    return 1;
  }
}
