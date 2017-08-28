import React from 'react';
import { connect } from 'react-redux';
import Select, {Creatable} from 'react-select';

import fetchAppsAction from 'actions/AppActions';
import debounce from 'utils/debounce';
import styles from './AppSelectComponent.css';
import fetchPolicyAppsAction from 'actions/PolicyAppActions';

const noop = () => {};

class AppSelectComponent extends React.Component {
  componentDidMount() {
    this.props.isPolicyPage ? this.props.getPolicyApps() : this.props.getApps();
  }

  componentWillReceiveProps(nextProps) {
    if (nextProps.isPolicyPage !== this.props.isPolicyPage) {
      nextProps.isPolicyPage ? this.props.getPolicyApps() : this.props.getApps();
    }
  }

  render() {
    const finalApps = this.props.isPolicyPage ? this.props.policyApps : this.props.apps;
    const options = finalApps.asyncStatus === 'SUCCESS'
      ? finalApps.data.map(a => ({name: a})) : [];

    const noResultsText = finalApps.asyncStatus === 'SUCCESS'
    && finalApps.data.length === 0 ? 'No results found!' : 'Searching...';
    const valueOption = this.props.value && {name: this.props.value};
    return (
      <div>
        <label className={styles.label} htmlFor="appid">App</label>
        {this.props.isPolicyPage &&
        <Creatable
          clearable={false}
          id="appid"
          options={options}
          value={valueOption}
          onChange={this.props.onChange || noop}
          labelKey="name"
          valueKey="name"
          onInputChange={debounce(this.props.getPolicyApps, 500)}
          isLoading={finalApps.asyncStatus === 'PENDING'}
          noResultsText={noResultsText}
          placeholder="Type to search..."
          promptTextCreator={(label) => "Add app: " + label}
        />}
        {!this.props.isPolicyPage &&
        <Select
          clearable={false}
          id="appid"
          options={options}
          value={valueOption}
          onChange={this.props.onChange || noop}
          labelKey="name"
          valueKey="name"
          onInputChange={debounce(this.props.getApps, 500)}
          isLoading={finalApps.asyncStatus === 'PENDING'}
          noResultsText={noResultsText}
          placeholder="Type to search..."
        />}
      </div>
    );
  }
}

const mapStateToProps = state => ({ apps: state.apps, policyApps: state.policyApps });
const mapDispatchToProps = dispatch => ({
  getApps: prefix => dispatch(fetchAppsAction(prefix)),
  getPolicyApps: prefix => dispatch(fetchPolicyAppsAction(prefix))
});

export default connect(mapStateToProps, mapDispatchToProps)(AppSelectComponent);

