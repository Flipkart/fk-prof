import React from 'react';
import { connect } from 'react-redux';
import { withRouter } from 'react-router';
import Select from 'react-select';

import fetchAppIdsAction from 'actions/AppIdActions';
import styles from './AppIdSelectorComponent.css';

const noop = () => {};

const AppIdSelectorComponent = props => {
  const options = props.appIds.asyncStatus === 'SUCCESS'
    ? props.appIds.data.map(a => ({ name: a })) : [];

  const noResultsText = props.appIds.asyncStatus === 'SUCCESS'
    && props.appIds.data.length === 0 ? 'No results found!' : 'Searching...';
  const valueOption = props.value && { name: props.value };
  return (
    <div>
      <label className={styles.label} htmlFor="appid">App ID</label>
      <Select
        id="appid"
        options={options}
        value={valueOption}
        onChange={props.onChange || noop}
        labelKey="name"
        valueKey="name"
        onInputChange={props.getAppIds}
        isLoading={props.appIds.asyncStatus === 'PENDING'}
        noResultsText={noResultsText}
        placeholder="Type to search..."
      />
    </div>
  );
};

const mapStateToProps = state => ({ appIds: state.appIds });
const mapDispatchToProps = dispatch => ({
  getAppIds: prefix => dispatch(fetchAppIdsAction(prefix)),
});

export default connect(mapStateToProps, mapDispatchToProps)(withRouter(AppIdSelectorComponent));

